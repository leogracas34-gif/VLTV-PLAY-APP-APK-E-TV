package com.vltv.play

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sistema de autoatualização do app fora da Play Store.
 *
 * FLUXO:
 * 1. checarAtualizacao() busca https://cdn.vltvplay.tech/update/version.json na VPS
 * 2. Compara o "versionCode" remoto com o BuildConfig.VERSION_CODE instalado
 * 3. Se houver versão nova, retorna um UpdateInfo pra Activity mostrar o diálogo
 * 4. Ao usuário confirmar, iniciarDownload() baixa o APK via DownloadManager do Android
 * 5. Quando o download termina, o DownloadCompleteReceiver dispara uma notificação
 *    PRÓPRIA (não a genérica do sistema) com um botão "Toque para instalar".
 *
 * ⚠️ POR QUE NÃO ABRE A INSTALAÇÃO 100% SOZINHO:
 * A partir do Android 10, o sistema bloqueia qualquer app de abrir uma tela
 * (Activity) a partir de um processo em segundo plano sem interação direta
 * do usuário — é proteção contra apps abrindo telas sozinhos enquanto você
 * usa o celular. Por isso o download termina e a instalação não abre
 * automaticamente: precisa de UM toque do usuário (nessa notificação, ou
 * na notificação padrão do sistema) pra "contar" como interação válida.
 * Isso vale pra qualquer app — não é limitação do VLTV Play.
 *
 * IMPORTANTE: pra atualização instalar por cima de uma versão já instalada, o
 * APK novo precisa ser assinado com a MESMA chave da versão anterior. Por isso
 * o projeto usa uma keystore de debug fixa (debug.keystore na raiz do repo,
 * referenciada em app/build.gradle) em vez da keystore aleatória que o
 * GitHub Actions geraria por padrão a cada build.
 */
object AppUpdateManager {

    private const val VERSION_JSON_URL = "https://cdn.vltvplay.tech/update/version.json"
    private const val PREFS_NAME = "vltv_update_prefs"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_FILENAME = "apk_filename"
    private const val CHANNEL_ID = "vltv_update_channel"
    private const val NOTIFICATION_ID = 5501

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val obrigatorio: Boolean
    )

    sealed class CheckResult {
        data class UpdateDisponivel(val info: UpdateInfo) : CheckResult()
        object AtualizadoOuFalha : CheckResult()
    }

    /**
     * Busca o version.json na VPS e compara com a versão instalada.
     * Chamar sempre em background (já usa Dispatchers.IO internamente).
     */
    suspend fun checarAtualizacao(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(VERSION_JSON_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VLTVPlay-Android")
            }

            if (conn.responseCode !in 200..299) {
                return@withContext CheckResult.AtualizadoOuFalha
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val versionCodeRemoto = json.optInt("versionCode", -1)
            val versionName = json.optString("versionName", "")
            val apkUrl = json.optString("apkUrl", "")
            val changelog = json.optString("changelog", "")
            val obrigatorio = json.optBoolean("obrigatorio", false)

            val versionCodeAtual = BuildConfig.VERSION_CODE

            if (versionCodeRemoto > versionCodeAtual && apkUrl.isNotBlank()) {
                CheckResult.UpdateDisponivel(
                    UpdateInfo(versionCodeRemoto, versionName, apkUrl, changelog, obrigatorio)
                )
            } else {
                CheckResult.AtualizadoOuFalha
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CheckResult.AtualizadoOuFalha
        }
    }

    /**
     * Verifica se o app já tem permissão de instalar APKs de fontes desconhecidas.
     * No Android 8+ (Oreo) essa permissão é POR APP, não global.
     */
    fun podeInstalarApks(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Verifica se falta pedir a permissão POST_NOTIFICATIONS (só existe a
     * partir do Android 13). Chamar antes de iniciarDownload(), a partir de
     * uma Activity, pra garantir que a notificação "toque para instalar"
     * consiga aparecer depois.
     */
    fun precisaPedirPermissaoNotificacao(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    /** Abre a tela do sistema onde o usuário ativa "permitir instalar apps desconhecidos". */
    fun abrirTelaPermissaoInstalacao(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    /**
     * Inicia o download do APK via DownloadManager nativo do Android.
     * VISIBILITY_VISIBLE (sem NOTIFY_COMPLETED): mostra a barra de progresso
     * enquanto baixa, mas NÃO deixa o sistema criar a notificação genérica
     * de "concluído" — quem avisa o usuário agora é a notificação própria,
     * disparada pelo DownloadCompleteReceiver quando termina.
     */
    fun iniciarDownload(context: Context, info: UpdateInfo): Long {
        val nomeArquivo = "vltvplay-${info.versionName}.apk"

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("VLTV Play - Atualização")
            setDescription("Baixando versão ${info.versionName}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, nomeArquivo)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_APK_FILENAME, nomeArquivo)
            .putString("versao_baixada", info.versionName)
            .apply()

        return downloadId
    }

    /** Monta o Intent que abre a tela nativa de instalação do APK já baixado. */
    private fun montarIntentInstalacao(context: Context): Intent? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nomeArquivo = prefs.getString(KEY_APK_FILENAME, null) ?: return null

        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), nomeArquivo)
        if (!apkFile.exists()) return null

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Abre a tela nativa de instalação diretamente (usar quando chamado a
     * partir de uma interação já garantida do usuário, ex: clique num botão
     * dentro do próprio app).
     */
    fun instalarApkBaixado(context: Context) {
        val installIntent = montarIntentInstalacao(context) ?: return
        context.startActivity(installIntent)
    }

    /**
     * ✅ NOVO: mostra uma notificação PRÓPRIA (não a padrão do sistema)
     * assim que o download termina, com um botão "Toque para instalar".
     * O toque do usuário na notificação conta como interação válida pro
     * Android permitir abrir a tela de instalação a partir daí.
     */
    private fun mostrarNotificacaoInstalar(context: Context) {
        val installIntent = montarIntentInstalacao(context) ?: return

        // ✅ A partir do Android 13 (API 33), postar notificação exige a
        // permissão de runtime POST_NOTIFICATIONS concedida pelo usuário —
        // ela está declarada no manifest, mas precisa ser pedida em tela
        // (ver AppUpdateManager.pedirPermissaoNotificacao, chamado do
        // HomeActivity). Sem isso, NotificationManager.notify() não lança
        // erro nenhum, só não mostra nada — por isso essa checagem evita
        // a "notificação fantasma": se não tem permissão, a notificação
        // padrão do sistema (que já foi restaurada acima, no
        // VISIBILITY_VISIBLE_NOTIFY_COMPLETED) continua servindo de
        // rede de segurança.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissaoConcedida = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!permissaoConcedida) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Atualizações do VLTV Play",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa quando uma nova versão do app termina de baixar"
            }
            manager.createNotificationChannel(channel)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val versao = prefs.getString("versao_baixada", "")

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Atualização baixada")
            .setContentText("Toque para instalar a versão $versao")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Receiver que detecta quando o download do APK terminou e dispara
     * a notificação própria de "toque para instalar". Registrado no
     * AndroidManifest.xml.
     */
    class DownloadCompleteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val idRecebido = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val idSalvo = prefs.getLong(KEY_DOWNLOAD_ID, -1)

                if (idRecebido != -1L && idRecebido == idSalvo) {
                    mostrarNotificacaoInstalar(context)
                }
            }
        }
    }
}
