package com.vltv.play

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
 * 5. Quando o download termina, o DownloadCompleteReceiver dispara instalarApkBaixado(),
 *    que abre a tela nativa de instalação (exige confirmação manual do usuário —
 *    é limitação do próprio Android, não dá pra instalar 100% silencioso sem
 *    o app ser "device owner"/MDM).
 *
 * VPS: hospedar version.json + o .apk no mesmo esquema estático que já é usado
 * em cdn.vltvplay.tech/retro/ (Nginx/Express servindo arquivo estático).
 */
object AppUpdateManager {

    private const val VERSION_JSON_URL = "https://cdn.vltvplay.tech/update/version.json"
    private const val PREFS_NAME = "vltv_update_prefs"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_FILENAME = "apk_filename"

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

    /** Abre a tela do sistema onde o usuário ativa "permitir instalar apps desconhecidos". */
    fun abrirTelaPermissaoInstalacao(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    /** Inicia o download do APK via DownloadManager nativo do Android (mostra progresso na barra de notificações). */
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
            .apply()

        return downloadId
    }

    /** Abre a tela nativa de instalação do APK já baixado. Chamado automaticamente ao terminar o download. */
    fun instalarApkBaixado(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nomeArquivo = prefs.getString(KEY_APK_FILENAME, null) ?: return

        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), nomeArquivo)
        if (!apkFile.exists()) return

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(installIntent)
    }

    /**
     * Receiver que detecta quando o download do APK terminou e dispara
     * a instalação automaticamente. Precisa ser registrado no AndroidManifest.xml
     * (veja instruções abaixo do código).
     */
    class DownloadCompleteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val idRecebido = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val idSalvo = prefs.getLong(KEY_DOWNLOAD_ID, -1)

                if (idRecebido != -1L && idRecebido == idSalvo) {
                    instalarApkBaixado(context)
                }
            }
        }
    }
}
