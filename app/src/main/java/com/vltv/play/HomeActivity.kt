package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.vltv.play.databinding.ActivityHomeBinding
import com.vltv.play.DownloadHelper
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.retro.RetroGamesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val TMDB_API_KEY = "9b73f5dd15b8165b1b57419be2f29128"

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    private var listaCompletaParaSorteio: List<Any> = emptyList()
    private lateinit var bannerAdapter: BannerAdapter

    private val bannerFila = mutableListOf<Any>()
    private var bannerFilaIndex = 0
    private var bannerCarregado = false
    private var bannerItemAtual: Any? = null
    private var bannerBuscaJob: kotlinx.coroutines.Job? = null
    private val bannerHandler = Handler(Looper.getMainLooper())
    private val BANNER_INTERVALO_MS = 8000L

    private var bannerRequestId = 0

    private var gameBannerCrestJob: kotlinx.coroutines.Job? = null

    private data class GameInfo(
        val competition: String = "",
        val team_home: String = "",
        val team_away: String = "",
        val date: String = "",
        val time: String = "",
        val channel: String = "",
        val image_url: String = "",
        val is_live: Boolean = false
    )

    private data class GameDisplayReady(
        val info: GameInfo,
        val crestHome: Bitmap?,
        val crestAway: Bitmap?,
        val bitmapFundo: Bitmap? = null
    )

    private val gameRotationHandler = Handler(Looper.getMainLooper())
    private var gameRotationList: List<GameDisplayReady> = emptyList()
    private var gameRotationIndex = 0
    private var gameRotationFetchJob: kotlinx.coroutines.Job? = null
    private val GAME_ROTATION_INTERVALO_MS = 6000L

    // ✅ OTIMIZAÇÃO (banner de jogos / destaque):
    // Antes, TODA vez que a Home passava por onResume() -> setupFirebaseRemoteConfig(),
    // os escudos dos times e a imagem de fundo do confronto eram baixados/gerados de
    // novo do zero, mesmo que os jogos do dia fossem exatamente os mesmos de antes.
    // Isso competia por rede com o carregamento dos pôsteres de filmes/séries e fazia
    // a Home "demorar pra popular" sempre que o banner de jogos estava ativo no
    // Remote Config. Os campos abaixo guardam o que já foi resolvido nesta sessão
    // pra pular esse trabalho quando os dados não mudaram.
    private val escudoBitmapCache = mutableMapOf<String, Bitmap?>()
    private val confrontoBitmapCache = mutableMapOf<String, Bitmap?>()
    private var ultimoGamesJsonAplicado: String? = null
    private var ultimoFeaturedTitleAplicado: String? = null
    private var featuredBannerEncontrado = false

    private data class BannerAssets(
        val backdropUrl: String?,
        val logoUrl: String?,
        val cleanTitle: String
    )
    private val bannerAssetsCache = mutableMapOf<String, BannerAssets>()

    private var top10FilmesJob: kotlinx.coroutines.Job? = null
    private var top10SeriesJob: kotlinx.coroutines.Job? = null
    private var removerOuvinteSync: (() -> Unit)? = null

    private var popularSectionsJob: kotlinx.coroutines.Job? = null
    private var popularSectionsPendente: Triple<List<VodItem>, List<VodEntity>, List<SeriesEntity>>? = null

    // ✅ NOVO: evita checar atualização mais de uma vez por sessão da Activity
    // (onCreate roda uma vez só, então isso é mais por clareza/segurança).
    private var verificacaoAtualizacaoFeita = false

    companion object {
        private val REGEX_EXIBICAO_TAGS = Regex("(?i)\\b(4K|FULL\\.?HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT[-.]?BR|PTBR|WEB[-.]?DL|BLURAY|MKV|MP4|AVI|REPACK|H\\.?264|H\\.?265|HEVC|WEB|HDR|UHD|FHD|CINEMA|LAN[ÇC]AMENTO|EXCLUSIVO)\\b")
        private val REGEX_EXIBICAO_BRACKETS = Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}")
        private val REGEX_EXIBICAO_YEAR = Regex("\\d{4}")
        private val REGEX_EXIBICAO_SPACES = Regex("\\s{2,}")
        private val REGEX_EXIBICAO_TRAILING = Regex("[-|•·]+\\s*$")

        private val REGEX_TMDB_TAGS = Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|AUDIO|LATINO|PT-BR|PTBR|WEB-DL|BLURAY|MKV|MP4|AVI|REPACK|H264|H265|HEVC|WEB|S\\d+E\\d+|SEASON|TEMPORADA)\\b")
        private val REGEX_TMDB_BRACKETS = Regex("\\(\\d{4}\\)|\\[.*?\\]|\\{.*?\\}|\\(.*\\d{4}.*\\)")
        private val REGEX_TMDB_SPACES = Regex("\\s+")

        private const val WORDMARK_TAG = "vltv_home_wordmark"

        @Volatile private var ultimoFetchRemoteConfigMs = 0L
        private const val INTERVALO_MINIMO_FETCH_MS = 30_000L
    }

    /**
     * ✅ CORREÇÃO DO AVATAR CINZA NO RODAPÉ:
     * O BottomNavigationView aplica automaticamente um "itemIconTintList"
     * em cima de TODOS os ícones do menu (inclusive os que a gente define
     * programaticamente). Esse tint substitui as cores do bitmap por uma
     * cor sólida (cinza no estado não-selecionado), usando apenas o canal
     * alfa do drawable — por isso a foto do avatar vira só uma "silhueta"
     * cinza, sem nenhum detalhe visível.
     *
     * Esse Drawable "à prova de tint" ignora qualquer chamada de
     * setColorFilter/setTint (é assim que o BottomNavigationView aplica a
     * cor por baixo dos panos), então o avatar continua sendo desenhado
     * com as cores originais, enquanto os outros ícones do menu (home,
     * busca, novidades) continuam sendo tingidos normalmente.
     */
    private class UntintableDrawable(private val base: Drawable) : Drawable() {
        override fun draw(canvas: Canvas) = base.draw(canvas)
        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            // Ignorado de propósito: bloqueia o tint que o
            // BottomNavigationView tentaria aplicar sobre o avatar.
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = base.intrinsicWidth
        override fun getIntrinsicHeight(): Int = base.intrinsicHeight
        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            super.setBounds(left, top, right, bottom)
            base.setBounds(left, top, right, bottom)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            configurarOrientacaoAutomatica()

            binding = ActivityHomeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val savedName = prefs.getString("last_profile_name", null)
            val savedIcon = prefs.getString("last_profile_icon", null)

            currentProfile = intent.getStringExtra("PROFILE_NAME") ?: savedName ?: "Padrao"
            currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
                ?.takeIf { it.isNotEmpty() }
                ?: savedIcon?.takeIf { it.isNotEmpty() }

            prefs.edit().apply {
                putString("last_profile_name", currentProfile)
                if (currentProfileIcon != null) putString("last_profile_icon", currentProfileIcon)
                apply()
            }

            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false

            setupSingleBanner()
            setupBottomNavigation()
            setupClicks()

            adicionarWordmarkVLTV()

            if (ContentRepository.pronto) {
                popularTelaDoRepositorio()
            } else {
                ContentRepository.aoFicarPronto {
                    popularTelaDoRepositorio()
                }
                carregarDadosLocaisImediato()
            }

            SyncManager.sincronizarSeNecessario(applicationContext)
            SyncManager.iniciarSyncPeriodica(applicationContext)
            removerOuvinteSync = SyncManager.registrarOuvinteNovidade {
                if (!isFinishing && !isDestroyed) {
                    popularTelaDoRepositorio()
                }
            }

            verificarAtualizacaoApp()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * ✅ NOVO: SISTEMA DE AUTOATUALIZAÇÃO (fora da Play Store)
     *
     * Busca https://cdn.vltvplay.tech/update/version.json em background.
     * Se o "versionCode" de lá for maior que o BuildConfig.VERSION_CODE
     * instalado, mostra um diálogo oferecendo pra baixar e instalar a
     * nova versão. Ver AppUpdateManager.kt para o fluxo completo
     * (download via DownloadManager + instalação via FileProvider).
     *
     * Roda só uma vez por abertura da Home (onCreate), pra não ficar
     * batendo na VPS a cada onResume().
     */
    private fun verificarAtualizacaoApp() {
        if (verificacaoAtualizacaoFeita) return
        verificacaoAtualizacaoFeita = true

        lifecycleScope.launch {
            try {
                val resultado = AppUpdateManager.checarAtualizacao(applicationContext)
                if (!isFinishing && !isDestroyed &&
                    resultado is AppUpdateManager.CheckResult.UpdateDisponivel) {
                    mostrarDialogoAtualizacao(resultado.info)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun mostrarDialogoAtualizacao(info: AppUpdateManager.UpdateInfo) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Nova versão disponível: ${info.versionName}")
            .setMessage(
                info.changelog.ifBlank { "Uma nova atualização do VLTV Play está disponível." }
            )
            .setPositiveButton("Atualizar agora") { _, _ ->
                if (AppUpdateManager.podeInstalarApks(this)) {
                    if (AppUpdateManager.precisaPedirPermissaoNotificacao(this)) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                         this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001
                        )
                    }
                    AppUpdateManager.iniciarDownload(this, info)
                    Toast.makeText(this, "Baixando atualização...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Ative a permissão de instalação para continuar a atualização",
                        Toast.LENGTH_LONG
                    ).show()
                    AppUpdateManager.abrirTelaPermissaoInstalacao(this)
                }
            }

        if (!info.obrigatorio) {
            builder.setNegativeButton("Depois", null)
            builder.setCancelable(true)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun adicionarWordmarkVLTV() {
        val contentRoot = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        if (contentRoot.findViewWithTag<View>(WORDMARK_TAG) != null) return

        val statusBarHeightPx = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
        }

        val wordmark = TextView(this).apply {
            tag = WORDMARK_TAG
            text = "VLTV"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setShadowLayer(8f, 0f, 2f, Color.parseColor("#B3000000"))
            setPadding(20.dp, 0, 20.dp, 0)
            isClickable = false
            isFocusable = false
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = statusBarHeightPx + 10.dp
        }

        contentRoot.addView(wordmark, params)
    }

    private fun iniciarCarrosselBanner() {
        bannerHandler.removeCallbacksAndMessages(null)
        if (bannerFila.size < 2) return
        bannerHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                avancarBanner()
                bannerHandler.postDelayed(this, BANNER_INTERVALO_MS)
            }
        }, BANNER_INTERVALO_MS)
    }

    private fun avancarBanner() {
        if (bannerFila.isEmpty()) return
        bannerFilaIndex = (bannerFilaIndex + 1) % bannerFila.size
        val proximoItem = bannerFila[bannerFilaIndex]
        mostrarItemNoBanner(proximoItem)
    }

    private fun mostrarItemNoBanner(item: Any) {
        if (isFinishing || isDestroyed) return
        bannerItemAtual = item
        bannerAdapter.updateItem(item)
    }

    private fun construirFilaBannerEIniciar() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val top10Vods   = database.streamDao().getTop10Vods()
                val top10Series = database.streamDao().getTop10Series()

                val fila = mutableListOf<Any>()
                val maxLen = maxOf(top10Vods.size, top10Series.size)
                for (i in 0 until maxLen) {
                    if (i < top10Vods.size)   fila.add(top10Vods[i])
                    if (i < top10Series.size)  fila.add(top10Series[i])
                }

                val filaFinal: List<Any> = if (fila.isNotEmpty()) fila
                else listaCompletaParaSorteio.shuffled().take(20)

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (filaFinal.isEmpty()) return@withContext

                    bannerFila.clear()
                    bannerFila.addAll(filaFinal)
                    bannerFilaIndex = 0

                    if (!bannerCarregado) {
                        bannerCarregado = true
                        mostrarItemNoBanner(bannerFila[0])
                    }

                    iniciarCarrosselBanner()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (!bannerCarregado && listaCompletaParaSorteio.isNotEmpty()) {
                        bannerCarregado = true
                        mostrarItemNoBanner(listaCompletaParaSorteio.first())
                    }
                }
            }
        }
    }

    private fun popularTelaDoRepositorio() {
        val localMovies = ContentRepository.vods
        val localSeries = ContentRepository.series

        if (localMovies.isEmpty() && localSeries.isEmpty()) {
            carregarDadosLocaisImediato()
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val movieItems = localMovies.map {
                VodItem(it.stream_id.toString(), limparNomeExibicao(it.name), it.stream_icon ?: "")
            }
            val seriesItems = localSeries.map {
                VodItem(it.series_id.toString(), limparNomeExibicao(it.name), it.cover ?: "")
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                agendarPopularSections(movieItems, seriesItems, localMovies, localSeries)
            }
        }
    }

    private fun agendarPopularSections(
        movieItems: List<VodItem>,
        seriesItems: List<VodItem>,
        localMovies: List<VodEntity>,
        localSeries: List<SeriesEntity>
    ) {
        popularSectionsJob?.cancel()
        popularSectionsJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(300)
            if (isFinishing || isDestroyed) return@launch
            popularSections(movieItems, seriesItems, localMovies, localSeries)
        }
    }

    private fun popularSections(
        movieItems: List<VodItem>,
        seriesItems: List<VodItem>,
        localMovies: List<VodEntity>,
        localSeries: List<SeriesEntity>
    ) {
        if (localMovies.isNotEmpty()) {
            val filmesOrdenados = localMovies.sortedWith(
                compareByDescending<VodEntity> { it.is_novidade }
                    .thenByDescending { it.tmdb_release_date ?: "" }
                    .thenByDescending { it.added }
            )
            val listaExibicaoFilmes = filmesOrdenados.take(20).map {
                VodItem(it.stream_id.toString(), limparNomeExibicao(it.name), it.stream_icon ?: "")
            }

            binding.rvRecentlyAdded.setItemViewCacheSize(20)
            binding.rvRecentlyAdded.adapter = HomeRowAdapter(listaExibicaoFilmes) { selectedItem ->
                val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
                intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                intent.putExtra("name", selectedItem.name)
                intent.putExtra("icon", selectedItem.streamIcon)
                intent.putExtra("PROFILE_NAME", currentProfile)
                intent.putExtra("is_series", false)
                startActivity(intent)
            }
        }

        if (localSeries.isNotEmpty()) {
            val seriesOrdenadas = localSeries.sortedWith(
                compareByDescending<SeriesEntity> { it.is_novidade }
                    .thenByDescending { it.tmdb_release_date ?: "" }
                    .thenByDescending { it.last_modified }
            )
            val listaExibicaoSeries = seriesOrdenadas.take(20).map {
                VodItem(it.series_id.toString(), limparNomeExibicao(it.name), it.cover ?: "")
            }

            binding.rvRecentSeries.setItemViewCacheSize(20)
            binding.rvRecentSeries.adapter = HomeRowAdapter(listaExibicaoSeries) { selectedItem ->
                val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                intent.putExtra("name", selectedItem.name)
                intent.putExtra("icon", selectedItem.streamIcon)
                intent.putExtra("PROFILE_NAME", currentProfile)
                intent.putExtra("is_series", true)
                startActivity(intent)
            }
        }

        top10FilmesJob?.cancel()
        top10FilmesJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                var top10DbVods = database.streamDao().getTop10Vods()
                if (top10DbVods.size < 10) {
                    top10DbVods = buscarTop10FilmesAgora()
                }
                val top10Items = top10DbVods.map {
                    VodItem(it.stream_id.toString(), limparNomeExibicao(it.name), it.stream_icon ?: "")
                }
                val top10Final = if (top10Items.isNotEmpty()) top10Items else movieItems.take(10)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (top10Final.isNotEmpty()) {
                        binding.rvTop10Movies?.itemAnimator = null
                        binding.rvTop10Movies?.adapter = Top10Adapter(top10Final) { selectedItem ->
                            val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
                            intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", false)
                            startActivity(intent)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val fallback = movieItems.take(10)
                    if (fallback.isNotEmpty()) {
                        binding.rvTop10Movies?.itemAnimator = null
                        binding.rvTop10Movies?.adapter = Top10Adapter(fallback) { selectedItem ->
                            val intent = Intent(this@HomeActivity, DetailsActivity::class.java)
                            intent.putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", false)
                            startActivity(intent)
                        }
                    }
                }
            }
        }

        top10SeriesJob?.cancel()
        top10SeriesJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                var top10DbSeries = database.streamDao().getTop10Series()
                if (top10DbSeries.size < 10) {
                    top10DbSeries = buscarTop10SeriesAgora()
                }
                val top10Items = top10DbSeries.map {
                    VodItem(it.series_id.toString(), limparNomeExibicao(it.name), it.cover ?: "")
                }
                val top10Final = if (top10Items.isNotEmpty()) top10Items else seriesItems.take(10)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (top10Final.isNotEmpty()) {
                        binding.rvTop10Series?.itemAnimator = null
                        binding.rvTop10Series?.adapter = Top10Adapter(top10Final) { selectedItem ->
                            val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", true)
                            startActivity(intent)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val fallback = seriesItems.take(10)
                    if (fallback.isNotEmpty()) {
                        binding.rvTop10Series?.itemAnimator = null
                        binding.rvTop10Series?.adapter = Top10Adapter(fallback) { selectedItem ->
                            val intent = Intent(this@HomeActivity, SeriesDetailsActivity::class.java)
                            intent.putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0)
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            intent.putExtra("is_series", true)
                            startActivity(intent)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val novidadesDbFilmes = database.streamDao().getNovidadesVods()
                val novidadesDbSeries = database.streamDao().getNovidadesSeries()
                val novidades: List<VodItem>
                val seriesIds: Set<String>
                if (novidadesDbFilmes.isNotEmpty() || novidadesDbSeries.isNotEmpty()) {
                    val filmeItems = novidadesDbFilmes.map {
                        VodItem(it.stream_id.toString(), limparNomeExibicao(it.name), it.stream_icon ?: "")
                    }
                    val serieItems = novidadesDbSeries.map {
                        VodItem(it.series_id.toString(), limparNomeExibicao(it.name), it.cover ?: "")
                    }
                    novidades = (filmeItems + serieItems).take(20)
                    seriesIds = novidadesDbSeries.map { it.series_id.toString() }.toSet()
                } else {
                    val novidadesFilmes = movieItems.takeLast(10)
                    val novidadesSeries = seriesItems.takeLast(10)
                    novidades = (novidadesFilmes + novidadesSeries).shuffled().take(20)
                    seriesIds = novidadesSeries.map { it.id }.toSet()
                }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (novidades.isNotEmpty()) {
                        binding.rvNovidades?.itemAnimator = null
                        binding.rvNovidades?.adapter = HomeRowAdapter(novidades) { selectedItem ->
                            val ehSerie = seriesIds.contains(selectedItem.id)
                            val intent = if (ehSerie)
                                Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", selectedItem.id.toIntOrNull() ?: 0) }
                            else
                                Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", selectedItem.id.toIntOrNull() ?: 0) }
                            intent.putExtra("name", selectedItem.name)
                            intent.putExtra("icon", selectedItem.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        listaCompletaParaSorteio = (localMovies + localSeries)
        construirFilaBannerEIniciar()
        ativarModoSupersonico(movieItems, seriesItems)
        carregarContinuarAssistindoLocal()
    }

    private suspend fun buscarTop10FilmesAgora(): List<VodEntity> {
        return try {
            val tmdbUrl = "https://api.themoviedb.org/3/trending/movie/week?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"
            val tmdbResults = JSONObject(URL(tmdbUrl).readText()).getJSONArray("results")
            val resultado  = mutableListOf<VodEntity>()
            val idsUsados  = mutableSetOf<Int>()
            for (i in 0 until tmdbResults.length()) {
                if (resultado.size >= 10) break
                val obj        = tmdbResults.getJSONObject(i)
                val tituloPt   = obj.optString("title", "")
                val tituloOrig = obj.optString("original_title", "")
                val vod = queryVodEntityExato(tituloOrig, idsUsados)
                    ?: queryVodEntityExato(tituloPt, idsUsados)
                    ?: queryVodEntity(likeExato(tituloOrig), idsUsados)
                    ?: queryVodEntity(likeExato(tituloPt), idsUsados)
                    ?: palavraMaisLonga(tituloOrig)?.let { queryVodEntity("%$it%", idsUsados) }
                    ?: palavraMaisLonga(tituloPt)?.let { queryVodEntity("%$it%", idsUsados) }
                if (vod != null) { idsUsados.add(vod.stream_id); resultado.add(vod) }
            }
            resultado
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun buscarTop10SeriesAgora(): List<SeriesEntity> {
        return try {
            val tmdbUrl = "https://api.themoviedb.org/3/trending/tv/week?api_key=$TMDB_API_KEY&language=pt-BR&region=BR"
            val tmdbResults = JSONObject(URL(tmdbUrl).readText()).getJSONArray("results")
            val resultado  = mutableListOf<SeriesEntity>()
            val idsUsados  = mutableSetOf<Int>()
            for (i in 0 until tmdbResults.length()) {
                if (resultado.size >= 10) break
                val obj        = tmdbResults.getJSONObject(i)
                val tituloPt   = obj.optString("name", "")
                val tituloOrig = obj.optString("original_name", "")
                val serie = querySerieEntityExato(tituloOrig, idsUsados)
                    ?: querySerieEntityExato(tituloPt, idsUsados)
                    ?: querySerieEntity(likeExato(tituloOrig), idsUsados)
                    ?: querySerieEntity(likeExato(tituloPt), idsUsados)
                    ?: palavraMaisLonga(tituloOrig)?.let { querySerieEntity("%$it%", idsUsados) }
                    ?: palavraMaisLonga(tituloPt)?.let { querySerieEntity("%$it%", idsUsados) }
                if (serie != null) { idsUsados.add(serie.series_id); resultado.add(serie) }
            }
            resultado
        } catch (e: Exception) { emptyList() }
    }

    private fun normalizarTituloParaMatch(titulo: String): String {
        return titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR)\\b"), "")
            .trim()
    }

    private suspend fun queryVodEntityExato(titulo: String, excluir: Set<Int>): VodEntity? =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(titulo)
            if (tituloLimpo.isBlank()) return@withContext null
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, name, title, stream_icon, container_extension, rating, " +
                "category_id, added, logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM vod_streams WHERE name = ? COLLATE NOCASE LIMIT 10",
                arrayOf(tituloLimpo)
            )
            var resultado: VodEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = VodEntity(
                        stream_id           = id,
                        name                = cursor.getString(1),
                        title               = cursor.getString(2),
                        stream_icon         = cursor.getString(3),
                        container_extension = cursor.getString(4),
                        rating              = cursor.getString(5),
                        category_id         = cursor.getString(6),
                        added               = cursor.getLong(7),
                        logo_url            = cursor.getString(8),
                        tmdb_rank           = cursor.getInt(9),
                        tmdb_release_date   = cursor.getString(10),
                        is_top10            = cursor.getInt(11),
                        is_novidade         = cursor.getInt(12),
                        tmdb_id             = if (cursor.isNull(13)) null else cursor.getInt(13),
                        backdrop_path       = cursor.getString(14)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private suspend fun querySerieEntityExato(titulo: String, excluir: Set<Int>): SeriesEntity? =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(titulo)
            if (tituloLimpo.isBlank()) return@withContext null
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM series_streams WHERE name = ? COLLATE NOCASE LIMIT 10",
                arrayOf(tituloLimpo)
            )
            var resultado: SeriesEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = SeriesEntity(
                        series_id         = id,
                        name              = cursor.getString(1),
                        cover             = cursor.getString(2),
                        rating            = cursor.getString(3),
                        category_id       = cursor.getString(4),
                        last_modified     = cursor.getLong(5),
                        logo_url          = cursor.getString(6),
                        tmdb_rank         = cursor.getInt(7),
                        tmdb_release_date = cursor.getString(8),
                        is_top10          = cursor.getInt(9),
                        is_novidade       = cursor.getInt(10),
                        tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                        backdrop_path     = cursor.getString(12)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private suspend fun queryVodEntity(pattern: String, excluir: Set<Int>): VodEntity? =
        withContext(Dispatchers.IO) {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, name, title, stream_icon, container_extension, rating, " +
                "category_id, added, logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM vod_streams WHERE name LIKE ? ORDER BY LENGTH(name) ASC LIMIT 10",
                arrayOf(pattern)
            )
            var resultado: VodEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = VodEntity(
                        stream_id           = id,
                        name                = cursor.getString(1),
                        title               = cursor.getString(2),
                        stream_icon         = cursor.getString(3),
                        container_extension = cursor.getString(4),
                        rating              = cursor.getString(5),
                        category_id         = cursor.getString(6),
                        added               = cursor.getLong(7),
                        logo_url            = cursor.getString(8),
                        tmdb_rank           = cursor.getInt(9),
                        tmdb_release_date   = cursor.getString(10),
                        is_top10            = cursor.getInt(11),
                        is_novidade         = cursor.getInt(12),
                        tmdb_id             = if (cursor.isNull(13)) null else cursor.getInt(13),
                        backdrop_path       = cursor.getString(14)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private suspend fun querySerieEntity(pattern: String, excluir: Set<Int>): SeriesEntity? =
        withContext(Dispatchers.IO) {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, name, cover, rating, category_id, last_modified, " +
                "logo_url, tmdb_rank, tmdb_release_date, is_top10, is_novidade, " +
                "tmdb_id, backdrop_path " +
                "FROM series_streams WHERE name LIKE ? ORDER BY LENGTH(name) ASC LIMIT 10",
                arrayOf(pattern)
            )
            var resultado: SeriesEntity? = null
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                if (!excluir.contains(id)) {
                    resultado = SeriesEntity(
                        series_id         = id,
                        name              = cursor.getString(1),
                        cover             = cursor.getString(2),
                        rating            = cursor.getString(3),
                        category_id       = cursor.getString(4),
                        last_modified     = cursor.getLong(5),
                        logo_url          = cursor.getString(6),
                        tmdb_rank         = cursor.getInt(7),
                        tmdb_release_date = cursor.getString(8),
                        is_top10          = cursor.getInt(9),
                        is_novidade       = cursor.getInt(10),
                        tmdb_id           = if (cursor.isNull(11)) null else cursor.getInt(11),
                        backdrop_path     = cursor.getString(12)
                    )
                    break
                }
            }
            cursor.close()
            resultado
        }

    private fun likeExato(titulo: String): String {
        val limpo = titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR)\\b"), "")
            .trim()
        return "%" + limpo
            .replace(Regex("[àáâãäå]"), "_")
            .replace(Regex("[èéêë]"), "_")
            .replace(Regex("[ìíîï]"), "_")
            .replace(Regex("[òóôõö]"), "_")
            .replace(Regex("[ùúûü]"), "_")
            .replace(Regex("[ç]"), "_")
            .replace(Regex("[ñ]"), "_") + "%"
    }

    private fun palavraMaisLonga(titulo: String): String? {
        if (titulo.isBlank()) return null
        return titulo.split(" ").filter { it.length >= 5 }.maxByOrNull { it.length }
            ?.replace(Regex("[àáâãäå]"), "a")
            ?.replace(Regex("[èéêë]"), "e")
            ?.replace(Regex("[ìíîï]"), "i")
            ?.replace(Regex("[òóôõö]"), "o")
            ?.replace(Regex("[ùúûü]"), "u")
            ?.replace(Regex("[ç]"), "c")
            ?.replace(Regex("[ñ]"), "n")
    }

    private fun configurarOrientacaoAutomatica() {
        // ✅ Usa a detecção central de DeviceUtils.kt (isTelevisionDevice()),
        // a mesma usada no resto desta Activity (foco de D-pad) e em todas
        // as outras telas do app. Antes havia uma função local isTVDevice()
        // com uma heurística extra de tamanho de tela que podia divergir
        // do resultado usado no resto do arquivo, fazendo o mesmo aparelho
        // ser tratado como "TV" na orientação e como "celular" no foco.
        requestedOrientation = if (isTelevisionDevice()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setupSingleBanner() {
        bannerAdapter = BannerAdapter(null)
        binding.bannerViewPager?.adapter = bannerAdapter
        binding.bannerViewPager?.isUserInputEnabled = false
    }

    /**
     * ✅ CORREÇÃO: os avatares escolhidos na tela de Perfis (ProfilesActivity)
     * são nomes de drawables locais (ex: "av_iron_man", "av_batman"), e NÃO
     * URLs. O Glide, quando recebe uma String, tenta interpretá-la como
     * URL/caminho — como "av_iron_man" não é uma URL válida, o load falhava
     * silenciosamente e o ícone do perfil no rodapé nunca era preenchido.
     *
     * Agora resolvemos o nome do drawable pra um resource ID (igual já é
     * feito em ProfilesActivity.exibirAvatar) antes de pedir pro Glide
     * carregar. Se por algum motivo currentProfileIcon vier como uma URL
     * de verdade (http/https), o fallback abaixo continua funcionando.
     */
    private fun setupBottomNavigation() {
        binding.bottomNavigation?.let { nav ->
            val profileItem = nav.menu.findItem(R.id.nav_profile)
            profileItem?.title = currentProfile

            if (!currentProfileIcon.isNullOrEmpty()) {
                val iconValue = currentProfileIcon!!
                val ehUrlRemota = iconValue.startsWith("http://") || iconValue.startsWith("https://")
                val resId = if (!ehUrlRemota) {
                    resources.getIdentifier(iconValue, "drawable", packageName)
                } else {
                    0
                }

                val requestBuilder = if (resId != 0) {
                    Glide.with(this).asBitmap().load(resId)
                } else {
                    Glide.with(this).asBitmap().load(iconValue)
                }

                requestBuilder
                    .circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(object : CustomTarget<Bitmap>(96, 96) {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            if (!isFinishing && !isDestroyed) {
                                profileItem?.icon = UntintableDrawable(BitmapDrawable(resources, resource))
                            }
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            }
        }

        binding.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_search -> {
                    val intent = Intent(this, SearchActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                R.id.nav_novidades -> {
                    val intent = Intent(this, NovidadesActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    startActivity(intent)
                    false
                }
                else -> false
            }
        }
    }

    private fun carregarDadosLocaisImediato() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localMovies = database.streamDao().getRecentVods(60)
                val movieItems = localMovies.map { VodItem(it.stream_id.toString(), limparNomeExibicao(it.name), it.stream_icon ?: "") }
                val localSeries = database.streamDao().getRecentSeries(60)
                val seriesItems = localSeries.map { VodItem(it.series_id.toString(), limparNomeExibicao(it.name), it.cover ?: "") }
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    agendarPopularSections(movieItems, seriesItems, localMovies, localSeries)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun ativarModoSupersonico(filmes: List<VodItem>, series: List<VodItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            val preloadList = filmes.take(20) + series.take(20)
            for (item in preloadList) {
                try {
                    if (!item.streamIcon.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            Glide.with(applicationContext)
                                .load(item.streamIcon)
                                .format(DecodeFormat.PREFER_RGB_565)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .onlyRetrieveFromCache(true)
                                .preload(180, 270)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun limparNomeExibicao(nome: String): String {
        return nome
            .replace(REGEX_EXIBICAO_TAGS, "")
            .replace(REGEX_EXIBICAO_BRACKETS, "")
            .replace(REGEX_EXIBICAO_YEAR, "")
            .replace(REGEX_EXIBICAO_SPACES, " ")
            .replace(REGEX_EXIBICAO_TRAILING, "")
            .trim()
    }

    private fun limparNomeParaTMDB(nome: String): String {
        return nome
            .replace(REGEX_TMDB_TAGS, "")
            .replace(REGEX_TMDB_BRACKETS, "")
            .replace(REGEX_TMDB_SPACES, " ")
            .trim()
            .take(50)
    }

    private fun aplicarBannerCompleto(
        imgBanner: ImageView,
        imgLogo: ImageView,
        tvTitle: TextView,
        backdropUrl: String,
        logoUrl: String?,
        cleanTitle: String
    ) {
        imgBanner.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            Glide.with(this@HomeActivity)
                .load(backdropUrl)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .dontAnimate()
                .into(imgBanner)
        } catch (e: Exception) {}

        if (!logoUrl.isNullOrEmpty()) {
            tvTitle.visibility = View.GONE
            imgLogo.visibility = View.VISIBLE
            try {
                Glide.with(this@HomeActivity)
                    .load(logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
                    .into(imgLogo)
            } catch (e: Exception) {}
        } else {
            imgLogo.visibility = View.GONE
            tvTitle.visibility = View.VISIBLE
            tvTitle.text = cleanTitle
        }
    }

    private fun resolverEAplicarBannerCompleto(
        titulo: String,
        cleanTitle: String,
        isSeries: Boolean,
        id: Int,
        fallbackIcon: String,
        chaveCache: String,
        requestId: Int,
        imgBanner: ImageView,
        imgLogo: ImageView,
        tvTitle: TextView,
        logoSalvoNoBanco: String?,
        tmdbIdSalvo: Int?,
        backdropPathSalvo: String?
    ) {
        bannerBuscaJob?.cancel()
        bannerBuscaJob = lifecycleScope.launch(Dispatchers.IO) {
            var backdropUrl: String? = null
            var logoUrl: String? = logoSalvoNoBanco?.takeIf { it.isNotEmpty() }
            val tipo = if (isSeries) "tv" else "movie"

            try {
                val temResolucaoConfiavel = tmdbIdSalvo != null && !backdropPathSalvo.isNullOrEmpty()

                if (temResolucaoConfiavel) {
                    backdropUrl = VpsConfig.tmdbImage(backdropPathSalvo!!, "original")
                    if (logoUrl == null) {
                        logoUrl = buscarMelhorLogoTmdb(tmdbIdSalvo.toString(), tipo, "")
                        if (logoUrl != null) {
                            try {
                                if (isSeries) database.streamDao().updateSeriesTmdbAssets(id, tmdbIdSalvo, backdropPathSalvo, logoUrl)
                                else database.streamDao().updateVodTmdbAssets(id, tmdbIdSalvo, backdropPathSalvo, logoUrl)
                            } catch (e: Exception) {}
                        }
                    }
                } else {
                    val nomeLimpo = limparNomeParaTMDB(titulo)
                    val query = URLEncoder.encode(nomeLimpo, "UTF-8")
                    val url = "https://api.themoviedb.org/3/search/$tipo?api_key=$TMDB_API_KEY&query=$query&language=pt-BR&region=BR"
                    val response = URL(url).readText()
                    val results = JSONObject(response).getJSONArray("results")

                    if (results.length() > 0) {
                        val obj = escolherMelhorResultadoTmdb(results, titulo, isSeries)

                        if (obj != null) {
                            val backdropPath = obj.optString("backdrop_path")
                            val tmdbIdStr = obj.optString("id")
                            val tmdbIdInt = tmdbIdStr.toIntOrNull()
                            val originalLanguage = obj.optString("original_language", "")

                            val backdropValido = backdropPath.isNotEmpty() && backdropPath != "null"
                            if (backdropValido) {
                                backdropUrl = VpsConfig.tmdbImage(backdropPath, "original")
                            }

                            logoUrl = buscarMelhorLogoTmdb(tmdbIdStr, tipo, originalLanguage)

                            if (tmdbIdInt != null && backdropValido) {
                                try {
                                    if (isSeries) database.streamDao().updateSeriesTmdbAssets(id, tmdbIdInt, backdropPath, logoUrl)
                                    else database.streamDao().updateVodTmdbAssets(id, tmdbIdInt, backdropPath, logoUrl)
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {}

            val backdropFinal = backdropUrl ?: fallbackIcon

            bannerAssetsCache[chaveCache] = BannerAssets(backdropUrl, logoUrl, cleanTitle)

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (requestId != bannerRequestId) return@withContext
                aplicarBannerCompleto(imgBanner, imgLogo, tvTitle, backdropFinal, logoUrl, cleanTitle)
            }
        }
    }

    private fun normalizarParaComparacaoTitulo(s: String): String {
        return s.lowercase()
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôõö]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun escolherMelhorResultadoTmdb(results: org.json.JSONArray, tituloLocal: String, isSeries: Boolean): JSONObject? {
        val alvo = normalizarParaComparacaoTitulo(limparNomeParaTMDB(tituloLocal))
        if (alvo.isBlank()) return null

        val limite = minOf(results.length(), 10)

        for (i in 0 until limite) {
            val cand = results.getJSONObject(i)
            val nomeCand     = if (isSeries) cand.optString("name") else cand.optString("title")
            val nomeOrigCand = if (isSeries) cand.optString("original_name") else cand.optString("original_title")
            val nCand     = normalizarParaComparacaoTitulo(nomeCand)
            val nOrigCand = normalizarParaComparacaoTitulo(nomeOrigCand)
            if (nCand == alvo || nOrigCand == alvo) return cand
        }

        for (i in 0 until limite) {
            val cand = results.getJSONObject(i)
            val nomeCand     = if (isSeries) cand.optString("name") else cand.optString("title")
            val nomeOrigCand = if (isSeries) cand.optString("original_name") else cand.optString("original_title")
            val nCand     = normalizarParaComparacaoTitulo(nomeCand)
            val nOrigCand = normalizarParaComparacaoTitulo(nomeOrigCand)
            val bateParcial = alvo.length >= 4 && (
                nCand.startsWith(alvo) || alvo.startsWith(nCand) ||
                nOrigCand.startsWith(alvo) || alvo.startsWith(nOrigCand)
            )
            if (bateParcial) return cand
        }

        return null
    }

    private fun buscarMelhorLogoTmdb(tmdbId: String, tipo: String, originalLanguage: String): String? {
        return try {
            val imagesUrl = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=$TMDB_API_KEY&include_image_language=pt,$originalLanguage,null"
            val imagesJson = URL(imagesUrl).readText()
            val imagesObj = JSONObject(imagesJson)
            if (!imagesObj.has("logos")) return null
            val logos = imagesObj.getJSONArray("logos")
            if (logos.length() == 0) return null

            var bestPath: String? = null

            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "pt") { bestPath = logo.getString("file_path"); break }
            }
            if (bestPath == null && originalLanguage.isNotBlank() && originalLanguage != "pt") {
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == originalLanguage) { bestPath = logo.getString("file_path"); break }
                }
            }
            if (bestPath == null) {
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    val lang = logo.optString("iso_639_1")
                    if (lang == "null" || lang == "xx" || lang.isEmpty()) { bestPath = logo.getString("file_path"); break }
                }
            }
            if (bestPath == null && logos.length() > 0) {
                bestPath = logos.getJSONObject(0).getString("file_path")
            }

            bestPath?.let { VpsConfig.tmdbImage(it, "w500") }
        } catch (e: Exception) { null }
    }

    // ✅ OTIMIZAÇÃO: cache em memória (escudoBitmapCache) evita baixar o
    // mesmo escudo de novo em toda rotação/onResume — o brasão de um time
    // não muda de imagem de um resume pro outro. Também aplicamos um
    // timeout de 4s no Glide.get() pra uma imagem lenta/travada não segurar
    // o carregamento do card de jogo indefinidamente.
    private suspend fun buscarEscudoBitmap(nomeTime: String, tamanhoPx: Int): Bitmap? {
        if (nomeTime.isBlank()) return null
        escudoBitmapCache[nomeTime]?.let { return it }
        return try {
            val badgeUrl = EscudoHelper.buscarEscudoUrl(nomeTime) ?: return null
            val bitmap = withContext(Dispatchers.IO) {
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(badgeUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit(tamanhoPx, tamanhoPx)
                    .get(4, TimeUnit.SECONDS)
            }
            escudoBitmapCache[nomeTime] = bitmap
            bitmap
        } catch (e: Exception) { null }
    }

    private fun montarTituloComEscudos(
        timeCasa: String,
        timeFora: String,
        escudoCasa: Bitmap?,
        escudoFora: Bitmap?,
        tamanhoPx: Int
    ): CharSequence {
        val sb = SpannableStringBuilder()
        if (escudoCasa != null) {
            val start = sb.length
            sb.append("*")
            val drawable = BitmapDrawable(resources, escudoCasa)
            drawable.setBounds(0, 0, tamanhoPx, tamanhoPx)
            sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        sb.append(timeCasa)
        sb.append("  ×  ")
        if (escudoFora != null) {
            val start = sb.length
            sb.append("*")
            val drawable = BitmapDrawable(resources, escudoFora)
            drawable.setBounds(0, 0, tamanhoPx, tamanhoPx)
            sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        sb.append(timeFora)
        return sb
    }

    // ✅ OTIMIZAÇÃO: a checagem do Remote Config agora é adiada em ~400ms
    // no onResume() (veja override abaixo) pra não competir por rede com
    // o carregamento inicial dos pôsteres de filmes/séries. O corpo desta
    // função continua igual — o ganho de performance vem principalmente
    // do cache em aplicarGameBannerRotacao()/aplicarFeaturedBanner() logo
    // abaixo, que evita refazer todo o trabalho pesado quando os dados
    // não mudaram desde a última vez.
    private fun setupFirebaseRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        remoteConfig.setDefaultsAsync(mapOf(
            "show_copa_icon"        to false,
            "show_game_banner"      to false,
            "game_banner_title"     to "",
            "game_banner_date"      to "",
            "game_banner_time"      to "",
            "game_banner_channel"   to "",
            "game_banner_image_url" to "",
            "game_banner_is_live"   to false,
            "game_banner_competition" to "",
            "game_banner_mode"      to "copa",
            "game_banner_team_home"   to "",
            "game_banner_team_away"   to "",
            "games_today_json"       to "",
            "show_featured_banner"  to false,
            "featured_title"        to "",
            "featured_synopsis"     to "",
            "featured_image_url"    to "",
            "featured_is_series"    to false,
            "show_retro_games"      to true
        ))
        remoteConfig.setConfigSettingsAsync(remoteConfigSettings {
            minimumFetchIntervalInSeconds = 30
        })

        val agora = System.currentTimeMillis()
        val jaBuscouRecente = (agora - ultimoFetchRemoteConfigMs) < INTERVALO_MINIMO_FETCH_MS

        if (jaBuscouRecente) {
            val restanteS = (INTERVALO_MINIMO_FETCH_MS - (agora - ultimoFetchRemoteConfigMs)) / 1000
            android.util.Log.d(
                "VLTV_RemoteConfig",
                "Pulando fetch (guard interno) — buscou há menos de ${INTERVALO_MINIMO_FETCH_MS / 1000}s, " +
                "faltam ~${restanteS}s. Reaplicando última config já ativada."
            )
            IconeSazonalHelper.aplicar(this)
            aplicarGameBanner(remoteConfig)
            aplicarFeaturedBanner(remoteConfig)
            aplicarRetroGamesCard(remoteConfig)
            return
        }

        ultimoFetchRemoteConfigMs = agora
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                android.util.Log.d(
                    "VLTV_RemoteConfig",
                    "fetchAndActivate OK — games_today_json='${remoteConfig.getString("games_today_json")}' " +
                    "show_game_banner=${remoteConfig.getBoolean("show_game_banner")} " +
                    "show_featured_banner=${remoteConfig.getBoolean("show_featured_banner")} " +
                    "featured_title='${remoteConfig.getString("featured_title")}' " +
                    "show_retro_games=${remoteConfig.getBoolean("show_retro_games")}"
                )
            } else {
                val erro = task.exception
                if (erro is com.google.firebase.remoteconfig.FirebaseRemoteConfigFetchThrottledException) {
                    val liberaEmS = (erro.throttleEndTimeMillis - System.currentTimeMillis()) / 1000
                    android.util.Log.w(
                        "VLTV_RemoteConfig",
                        "⚠️ FETCH THROTTLADO PELO FIREBASE — libera em ~${liberaEmS}s. " +
                        "A tela vai continuar mostrando a ÚLTIMA config que foi ativada com sucesso " +
                        "até esse tempo passar. Evite reabrir o app repetidamente enquanto testa."
                    )
                } else {
                    android.util.Log.w(
                        "VLTV_RemoteConfig",
                        "fetchAndActivate FALHOU: ${erro?.javaClass?.simpleName} - ${erro?.message}"
                    )
                }
            }
            IconeSazonalHelper.aplicar(this)
            aplicarGameBanner(remoteConfig)
            aplicarFeaturedBanner(remoteConfig)
            aplicarRetroGamesCard(remoteConfig)
        }
    }

    private fun aplicarRetroGamesCard(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val show = remoteConfig.getBoolean("show_retro_games")
        binding.cardRetroGames?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun aplicarGameBanner(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val gamesJson = remoteConfig.getString("games_today_json")
        aplicarGameBannerRotacao(remoteConfig, gamesJson)
    }

    private fun pararRotacaoJogos() {
        gameRotationHandler.removeCallbacksAndMessages(null)
        gameRotationFetchJob?.cancel()
        gameRotationList = emptyList()
        gameRotationIndex = 0
    }

    private fun aplicarGameBannerRotacao(
        remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig,
        gamesJson: String
    ) {
        val show = remoteConfig.getBoolean("show_game_banner")
        val card = binding.cardGameBanner ?: return

        if (!show) {
            card.visibility = View.GONE
            pararRotacaoJogos()
            ultimoGamesJsonAplicado = null
            return
        }

        // ✅ OTIMIZAÇÃO PRINCIPAL: se os jogos de hoje são exatamente os
        // mesmos da última vez que essa tela foi montada (mesmo JSON vindo
        // do Remote Config), não tem motivo pra baixar os escudos e gerar
        // a imagem de fundo do confronto de novo — isso só consome rede e
        // tempo à toa em TODO onResume(), e é a causa principal da Home
        // demorar pra popular quando o banner de jogos está ativo. Só
        // reaplicamos a rotação já pronta.
        if (gamesJson == ultimoGamesJsonAplicado && gameRotationList.isNotEmpty()) {
            card.visibility = View.VISIBLE
            if (gameRotationIndex !in gameRotationList.indices) gameRotationIndex = 0
            mostrarJogoRotacao(gameRotationIndex)
            iniciarRotacaoJogos()
            return
        }

        gameRotationFetchJob?.cancel()
        gameRotationFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            // ATENÇÃO: todo o corpo fica dentro de um try/catch amplo.
            // lifecycleScope usa um Job comum (não SupervisorJob) — uma
            // exceção não tratada aqui cancelaria o Job inteiro da Activity,
            // derrubando também as coroutines de Top10/Novidades/Continuar
            // Assistindo que nada têm a ver com o banner de jogos. Por isso
            // qualquer falha (rede, escudo, geração do fundo) só esconde o
            // card do jogo, sem afetar o resto da Home.
            try {
                val jogos = try {
                    parseJogosDoDia(gamesJson)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }

                if (jogos.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) card.visibility = View.GONE
                    }
                    return@launch
                }

                val tamanhoPx = 28.dp

                // ✅ OTIMIZAÇÃO: escudos/fundos de TODOS os jogos são
                // buscados em PARALELO agora (antes era sequencial — um
                // jogo esperava o escudo do outro terminar de baixar antes
                // de começar o próximo, o que multiplicava o tempo total
                // pelo número de jogos do dia).
                val prontos = coroutineScope {
                    jogos.map { info ->
                        async {
                            val casa = try { buscarEscudoBitmap(info.team_home, tamanhoPx) } catch (e: Exception) { null }
                            val fora = try { buscarEscudoBitmap(info.team_away, tamanhoPx) } catch (e: Exception) { null }
                            val fundo = if (info.image_url.isBlank()) {
                                // Não veio image_url no JSON: monta o fundo do confronto
                                // automaticamente (degradê + escudos) via ConfrontoImageHelper.
                                // Também cacheado por par de times pra não regerar à toa.
                                val chaveFundo = "${info.team_home}|${info.team_away}"
                                if (confrontoBitmapCache.containsKey(chaveFundo)) {
                                    confrontoBitmapCache[chaveFundo]
                                } else {
                                    val gerado = try {
                                        ConfrontoImageHelper.gerarImagemFundo(info.team_home, info.team_away)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }
                                    confrontoBitmapCache[chaveFundo] = gerado
                                    gerado
                                }
                            } else {
                                null
                            }
                            GameDisplayReady(info, casa, fora, fundo)
                        }
                    }.awaitAll()
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    card.visibility = View.VISIBLE
                    gameRotationList = prontos
                    gameRotationIndex = 0
                    mostrarJogoRotacao(0)
                    iniciarRotacaoJogos()
                    ultimoGamesJsonAplicado = gamesJson

                    card.setOnClickListener {
                        val intent = Intent(this@HomeActivity, LiveTvActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", true)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) card.visibility = View.GONE
                }
            }
        }
    }

    private fun parseJogosDoDia(json: String): List<GameInfo> {
        val arr = org.json.JSONArray(json)
        val lista = mutableListOf<GameInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            lista.add(
                GameInfo(
                    competition = obj.optString("competition", ""),
                    team_home   = obj.optString("team_home", ""),
                    team_away   = obj.optString("team_away", ""),
                    date        = obj.optString("date", ""),
                    time        = obj.optString("time", ""),
                    channel     = obj.optString("channel", ""),
                    image_url   = obj.optString("image_url", ""),
                    is_live     = obj.optBoolean("is_live", false)
                )
            )
        }
        return lista.filter { it.team_home.isNotBlank() && it.team_away.isNotBlank() }
    }

    private fun iniciarRotacaoJogos() {
        gameRotationHandler.removeCallbacksAndMessages(null)
        if (gameRotationList.size < 2) return
        gameRotationHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                gameRotationIndex = (gameRotationIndex + 1) % gameRotationList.size
                mostrarJogoRotacao(gameRotationIndex)
                gameRotationHandler.postDelayed(this, GAME_ROTATION_INTERVALO_MS)
            }
        }, GAME_ROTATION_INTERVALO_MS)
    }

    private fun mostrarJogoRotacao(index: Int) {
        if (isFinishing || isDestroyed) return
        val card = binding.cardGameBanner ?: return
        if (index !in gameRotationList.indices) return
        val jogo = gameRotationList[index]
        val info = jogo.info

        val tvBadge = card.findViewById<TextView>(R.id.tvGameBadge)
        val statusTexto = if (info.is_live) "AO VIVO" else "EM BREVE"
        tvBadge.text = if (info.competition.isNotBlank()) "⚽  ${info.competition}  •  $statusTexto" else "⚽  $statusTexto"
        if (info.is_live) {
            tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#CC1B5E20"))
            tvBadge.setTextColor(android.graphics.Color.parseColor("#00FF88"))
        } else {
            tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#88333344"))
            tvBadge.setTextColor(android.graphics.Color.WHITE)
        }

        val tvGameTitle = card.findViewById<TextView>(R.id.tvGameTitle)
        val tamanhoPx = 28.dp
        tvGameTitle.text = if (jogo.crestHome != null || jogo.crestAway != null)
            montarTituloComEscudos(info.team_home, info.team_away, jogo.crestHome, jogo.crestAway, tamanhoPx)
        else
            "${info.team_home}  ×  ${info.team_away}"

        val datetime = buildString {
            if (info.date.isNotBlank()) append(info.date)
            if (info.date.isNotBlank() && info.time.isNotBlank()) append("  •  ")
            if (info.time.isNotBlank()) append(info.time)
            if (info.time.isNotBlank()) append(" (Brasília)")
        }
        card.findViewById<TextView>(R.id.tvGameDatetime).text = datetime

        val tvChannel = card.findViewById<TextView>(R.id.tvGameChannel)
        if (info.channel.isNotBlank()) {
            tvChannel.text = "📺  ${info.channel}"
            tvChannel.visibility = View.VISIBLE
        } else {
            tvChannel.visibility = View.GONE
        }

        val imgFundo = card.findViewById<ImageView>(R.id.imgGameBanner)
        imgFundo.scaleType = ImageView.ScaleType.CENTER_CROP
        if (info.image_url.isNotBlank()) {
            try {
                Glide.with(this).load(info.image_url).centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
                    .into(imgFundo)
            } catch (e: Exception) { e.printStackTrace() }
        } else if (jogo.bitmapFundo != null) {
            // Sem image_url: usa o fundo gerado localmente (degradê + escudos)
            Glide.with(this).clear(imgFundo)
            imgFundo.setImageBitmap(jogo.bitmapFundo)
        } else {
            // Nem image_url nem bitmap gerado (ex: escudos não encontrados e falha grave)
            Glide.with(this).clear(imgFundo)
            imgFundo.setImageDrawable(null)
        }
    }

    private fun aplicarFeaturedBanner(remoteConfig: com.google.firebase.remoteconfig.FirebaseRemoteConfig) {
        val show        = remoteConfig.getBoolean("show_featured_banner")
        val title       = remoteConfig.getString("featured_title")
        val synopsis    = remoteConfig.getString("featured_synopsis")
        val imageUrl    = remoteConfig.getString("featured_image_url")
        val isSeriesRC  = remoteConfig.getBoolean("featured_is_series")

        val card = binding.cardFeaturedBanner ?: return
        if (!show || title.isBlank()) {
            card.visibility = View.GONE
            ultimoFeaturedTitleAplicado = null
            featuredBannerEncontrado = false
            return
        }

        card.findViewById<TextView>(R.id.tvFeaturedTitle).text = title

        val tvSynopsis = card.findViewById<TextView>(R.id.tvFeaturedSynopsis)
        if (synopsis.isNotBlank()) {
            tvSynopsis.text = synopsis
            tvSynopsis.visibility = View.VISIBLE
        } else {
            tvSynopsis.visibility = View.GONE
        }

        if (imageUrl.isNotBlank()) {
            try {
                Glide.with(this).load(imageUrl).centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(card.findViewById(R.id.imgFeaturedBanner))
            } catch (e: Exception) { e.printStackTrace() }
        }

        card.visibility = View.VISIBLE

        // ✅ OTIMIZAÇÃO: se o título em destaque não mudou desde a última
        // vez e já tínhamos encontrado o item correspondente no banco, não
        // repete a busca (que envolve consultas LIKE, potencialmente
        // full-table-scan) a cada onResume(). Os cliques continuam
        // funcionando normalmente pois os listeners já foram configurados
        // na resolução anterior nesta mesma Activity.
        if (title == ultimoFeaturedTitleAplicado && featuredBannerEncontrado) {
            return
        }

        buscarIdFeaturedBanner(card, title, isSeriesRC)
        if (!ContentRepository.pronto) {
            ContentRepository.aoFicarPronto {
                if (!isFinishing && !isDestroyed) {
                    buscarIdFeaturedBanner(card, title, isSeriesRC)
                }
            }
        }
    }

    private fun buscarIdFeaturedBanner(
        card: androidx.cardview.widget.CardView,
        title: String,
        isSeriesRC: Boolean
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val semExclusao = emptySet<Int>()
                var streamId: Int? = null
                var seriesId: Int? = null
                var iconResolvido = ""

                if (isSeriesRC) {
                    val serie = querySerieEntityExato(title, semExclusao)
                        ?: querySerieEntity(likeExato(title), semExclusao)
                        ?: palavraMaisLonga(title)?.let { querySerieEntity("%$it%", semExclusao) }
                    if (serie != null) {
                        seriesId = serie.series_id
                        iconResolvido = serie.cover ?: ""
                    }
                } else {
                    val vod = queryVodEntityExato(title, semExclusao)
                        ?: queryVodEntity(likeExato(title), semExclusao)
                        ?: palavraMaisLonga(title)?.let { queryVodEntity("%$it%", semExclusao) }
                    if (vod != null) {
                        streamId = vod.stream_id
                        iconResolvido = vod.stream_icon ?: ""
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    val encontrado = streamId != null || seriesId != null
                    if (!encontrado) {
                        if (ContentRepository.pronto) {
                            card.visibility = View.GONE
                        }
                        return@withContext
                    }

                    card.visibility = View.VISIBLE
                    ultimoFeaturedTitleAplicado = title
                    featuredBannerEncontrado = true

                    val launchDetail: () -> Unit = {
                        val intent = if (isSeriesRC)
                            Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", seriesId ?: 0) }
                        else
                            Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", streamId ?: 0) }
                        intent.putExtra("name", title)
                        intent.putExtra("icon", iconResolvido)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("is_series", isSeriesRC)
                        startActivity(intent)
                    }

                    card.findViewById<View>(R.id.btnFeaturedAssistir).setOnClickListener { launchDetail() }
                    card.findViewById<View>(R.id.btnFeaturedDetalhes).setOnClickListener { launchDetail() }
                    card.setOnClickListener { launchDetail() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && ContentRepository.pronto) card.visibility = View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // ✅ OTIMIZAÇÃO: a checagem do Remote Config (banner de jogos e
            // banner em destaque) é adiada em ~400ms aqui, pra não competir
            // por rede com o carregamento inicial dos pôsteres de
            // filmes/séries que acontece assim que a Home abre/volta ao
            // primeiro plano. Combinado com o cache em
            // aplicarGameBannerRotacao()/aplicarFeaturedBanner() acima, a
            // Home volta a popular rápido mesmo com os banners ativos.
            lifecycleScope.launch(Dispatchers.Main) {
                delay(400)
                if (!isFinishing && !isDestroyed) {
                    setupFirebaseRemoteConfig()
                }
            }

            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            currentProfile = prefs.getString("last_profile_name", currentProfile) ?: "Padrao"
            currentProfileIcon = prefs.getString("last_profile_icon", currentProfileIcon)
                ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon

            if (bannerFila.size >= 2) {
                bannerFilaIndex = (bannerFilaIndex + 1) % bannerFila.size
                mostrarItemNoBanner(bannerFila[bannerFilaIndex])
                iniciarCarrosselBanner()
            } else if (bannerFila.size == 1) {
                iniciarCarrosselBanner()
            }

            if (gameRotationList.size >= 2) {
                iniciarRotacaoJogos()
            }

            carregarContinuarAssistindoLocal()
            setupBottomNavigation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        bannerHandler.removeCallbacksAndMessages(null)
        gameRotationHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerHandler.removeCallbacksAndMessages(null)
        bannerBuscaJob?.cancel()
        gameBannerCrestJob?.cancel()
        gameRotationHandler.removeCallbacksAndMessages(null)
        gameRotationFetchJob?.cancel()
        top10FilmesJob?.cancel()
        top10SeriesJob?.cancel()
        removerOuvinteSync?.invoke()
        removerOuvinteSync = null
    }

    private fun setupClicks() {
        // Detecção de TV centralizada em DeviceUtils.kt (isTelevisionDevice()),
        // usada em todo o app — não reimplementar localmente aqui.

        val cards = listOfNotNull(binding.cardLiveTv, binding.cardMovies, binding.cardSeries, binding.cardDownloads, binding.cardRetroGames)
        cards.forEach { card ->
            card.isFocusable = true
            card.isClickable = true
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.animate().scaleX(1.08f).scaleY(1.08f).translationZ(10f).setDuration(200).start()
                } else {
                    card.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            card.setOnClickListener {
                when (card.id) {
                    R.id.cardLiveTv -> {
                        val intent = Intent(this, LiveTvActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", true)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardMovies -> {
                        val intent = Intent(this, VodActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardSeries -> {
                        val intent = Intent(this, SeriesActivity::class.java)
                        intent.putExtra("SHOW_PREVIEW", false)
                        intent.putExtra("PROFILE_NAME", currentProfile)
                        intent.putExtra("PROFILE_ICON", currentProfileIcon)
                        startActivity(intent)
                    }
                    R.id.cardDownloads -> {
                        startActivity(Intent(this, DownloadsActivity::class.java))
                    }
                    R.id.cardRetroGames -> {
                        startActivity(Intent(this, RetroGamesActivity::class.java))
                    }
                }
            }
        }

        if (isTelevisionDevice()) {
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
            binding.cardMovies.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardLiveTv.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
            binding.cardSeries.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardMovies.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardDownloads.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
            binding.cardDownloads.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    binding.cardSeries.requestFocus(); true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    binding.bannerViewPager?.requestFocus(); true
                } else false
            }
        }

        setupBannerFocusParaTv()
    }

    /**
     * cardGameBanner e cardFeaturedBanner hoje só existem no layout de
     * celular (layout-port) — não aparecem no layout de TV (layout), então
     * `binding.cardGameBanner` / `binding.cardFeaturedBanner` ficam `null`
     * quando rodando em TV, e as funções abaixo simplesmente não fazem nada.
     *
     * Ainda assim deixamos o foco e a navegação por D-pad prontos aqui,
     * pra caso esses cards sejam adicionados futuramente também no layout
     * de TV — sem isso, o controle remoto não conseguiria alcançá-los,
     * mesmo com o card visível na tela (só o clique por toque funcionaria).
     */
    private fun setupBannerFocusParaTv() {
        if (!isTelevisionDevice()) return

        binding.cardFeaturedBanner?.let { featured ->
            featured.isFocusable = true
            featured.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    featured.animate().scaleX(1.04f).scaleY(1.04f).translationZ(10f).setDuration(200).start()
                } else {
                    featured.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            featured.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    (binding.cardGameBanner ?: binding.cardLiveTv).requestFocus(); true
                } else false
            }
        }

        binding.cardGameBanner?.let { game ->
            game.isFocusable = true
            game.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    game.animate().scaleX(1.04f).scaleY(1.04f).translationZ(10f).setDuration(200).start()
                } else {
                    game.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start()
                }
            }
            game.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        (binding.cardFeaturedBanner ?: binding.bannerViewPager)?.requestFocus(); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.cardLiveTv.requestFocus(); true
                    }
                    else -> false
                }
            }
        }

        // Se cardFeaturedBanner/cardGameBanner existirem, conecta a linha de
        // cima (bannerViewPager) e a linha de baixo (cardLiveTv) até eles,
        // pra fechar a cadeia de D-pad nos dois sentidos.
        binding.bannerViewPager?.let { pager ->
            val destinoAbaixo = binding.cardFeaturedBanner ?: binding.cardGameBanner ?: binding.cardLiveTv
            pager.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    destinoAbaixo.requestFocus(); true
                } else false
            }
        }
        if (binding.cardFeaturedBanner != null || binding.cardGameBanner != null) {
            val origemAcima = binding.cardFeaturedBanner ?: binding.cardGameBanner
            binding.cardLiveTv.setOnKeyListener { _, keyCode, event ->
                when {
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN -> {
                        binding.cardMovies.requestFocus(); true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                        origemAcima?.requestFocus(); true
                    }
                    else -> false
                }
            }
        }
    }

    private fun mostrarDialogoSair() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair e desconectar?")
            .setPositiveButton("Sim") { _, _ ->
                getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_home_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit().clear().apply()
                ContentRepository.limpar()
                SyncManager.resetarSessao()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Não", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mostrarDialogoSair()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun carregarContinuarAssistindoLocal() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyList = database.streamDao().getWatchHistory(currentProfile, 20)
                val vodItems = mutableListOf<VodItem>()
                val seriesMap = mutableMapOf<String, Boolean>()
                val seriesJaAdicionadas = mutableSetOf<String>()

                for (item in historyList) {
                    var finalId = item.stream_id.toString()
                    var finalName = limparNomeExibicao(item.name)
                    var finalIcon = item.icon ?: ""
                    val isSeries = item.is_series

                    if (isSeries) {
                        try {
                            var cleanName = item.name.replace(Regex("(?i)^(S\\d+E\\d+|T\\d+E\\d+|\\d+x\\d+|E\\d+)\\s*(-|:)?\\s*"), "")
                            if (cleanName.contains(":")) cleanName = cleanName.substringBefore(":")
                            cleanName = cleanName.replace(Regex("(?i)\\s+(S\\d+|T\\d+|E\\d+|Ep\\d+|Temporada|Season|Episode|Capitulo|\\d+x\\d+).*"), "")
                            if (cleanName.contains(" - ")) cleanName = cleanName.substringBefore(" - ")
                            cleanName = cleanName.trim()

                            val semExclusao = emptySet<Int>()
                            val serieResolvida = querySerieEntityExato(cleanName, semExclusao)
                                ?: querySerieEntity(likeExato(cleanName), semExclusao)
                                ?: palavraMaisLonga(cleanName)?.let { querySerieEntity("%$it%", semExclusao) }

                            if (serieResolvida != null) {
                                val realSeriesId = serieResolvida.series_id.toString()
                                if (seriesJaAdicionadas.contains(realSeriesId)) {
                                    continue
                                }
                                finalId = realSeriesId
                                finalName = limparNomeExibicao(serieResolvida.name)
                                finalIcon = serieResolvida.cover ?: ""
                                seriesJaAdicionadas.add(realSeriesId)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }

                    vodItems.add(VodItem(finalId, finalName, finalIcon))
                    seriesMap[finalId] = isSeries
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val tvTitle = binding.root.findViewById<TextView>(R.id.tvContinueWatching)
                    if (vodItems.isNotEmpty()) {
                        binding.layoutContinueHeader?.visibility = View.VISIBLE
                        tvTitle?.visibility = View.VISIBLE
                        binding.rvContinueWatching.visibility = View.VISIBLE
                        binding.rvContinueWatching.adapter = HomeRowAdapter(vodItems) { selected ->
                            val isSeries = seriesMap[selected.id] ?: false
                            val intent = if (isSeries) {
                                Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply {
                                    putExtra("series_id", selected.id.toIntOrNull() ?: 0)
                                }
                            } else {
                                Intent(this@HomeActivity, DetailsActivity::class.java).apply {
                                    putExtra("stream_id", selected.id.toIntOrNull() ?: 0)
                                }
                            }
                            intent.putExtra("name", selected.name)
                            intent.putExtra("icon", selected.streamIcon)
                            intent.putExtra("PROFILE_NAME", currentProfile)
                            startActivity(intent)
                        }
                    } else {
                        binding.layoutContinueHeader?.visibility = View.GONE
                        tvTitle?.visibility = View.GONE
                        binding.rvContinueWatching.visibility = View.GONE
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    inner class BannerAdapter(private var currentItem: Any?) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

        fun updateItem(newItem: Any) {
            currentItem = newItem
            notifyItemChanged(0)
        }

        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_banner_home, parent, false)
            return BannerViewHolder(view)
        }

        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
            currentItem?.let { holder.bind(it) }
        }

        inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgBanner: ImageView = itemView.findViewById(R.id.imgBanner)
            private val tvTitle: TextView    = itemView.findViewById(R.id.tvBannerTitle)
            private val imgLogo: ImageView   = itemView.findViewById(R.id.imgBannerLogo)
            private val btnPlay: View        = itemView.findViewById(R.id.btnBannerPlay)
            private val btnInfo: View?       = try { itemView.findViewById(R.id.btnBannerInfo) } catch (e: Exception) { null }

            fun bind(item: Any) {
                bannerRequestId++
                val meuRequestId = bannerRequestId

                var title    = ""
                var icon     = ""
                var id       = 0
                var isSeries = false
                var logoSalva: String? = null
                var tmdbIdSalvo: Int? = null
                var backdropPathSalvo: String? = null

                when (item) {
                    is VodEntity    -> {
                        title = item.name; icon = item.stream_icon ?: ""; id = item.stream_id; isSeries = false
                        logoSalva = item.logo_url; tmdbIdSalvo = item.tmdb_id; backdropPathSalvo = item.backdrop_path
                    }
                    is SeriesEntity -> {
                        title = item.name; icon = item.cover ?: "";       id = item.series_id; isSeries = true
                        logoSalva = item.logo_url; tmdbIdSalvo = item.tmdb_id; backdropPathSalvo = item.backdrop_path
                    }
                }

                val cleanTitle = limparNomeExibicao(title)
                val chaveCache = "${if (isSeries) "tv" else "movie"}_$id"

                val launchDetail: () -> Unit = {
                    val intent = if (isSeries)
                        Intent(this@HomeActivity, SeriesDetailsActivity::class.java).apply { putExtra("series_id", id) }
                    else
                        Intent(this@HomeActivity, DetailsActivity::class.java).apply { putExtra("stream_id", id) }
                    intent.putExtra("name", title)
                    intent.putExtra("icon", icon)
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("is_series", isSeries)
                    startActivity(intent)
                }
                btnPlay.setOnClickListener { launchDetail() }
                btnInfo?.setOnClickListener { launchDetail() }
                itemView.setOnClickListener { launchDetail() }

                val cacheado = bannerAssetsCache[chaveCache]
                if (cacheado != null) {
                    aplicarBannerCompleto(imgBanner, imgLogo, tvTitle, cacheado.backdropUrl ?: icon, cacheado.logoUrl, cacheado.cleanTitle)
                    return
                }

                resolverEAplicarBannerCompleto(
                    titulo = title, cleanTitle = cleanTitle, isSeries = isSeries, id = id,
                    fallbackIcon = icon, chaveCache = chaveCache, requestId = meuRequestId,
                    imgBanner = imgBanner, imgLogo = imgLogo, tvTitle = tvTitle,
                    logoSalvoNoBanco = logoSalva,
                    tmdbIdSalvo = tmdbIdSalvo,
                    backdropPathSalvo = backdropPathSalvo
                )
            }
        }
    }

    inner class Top10Adapter(
        private val list: List<VodItem>,
        private val onItemClick: (VodItem) -> Unit
    ) : RecyclerView.Adapter<Top10Adapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
            val tvRank: TextView    = view.findViewById(R.id.tvRankNumber)
            val tvTitle: TextView   = view.findViewById(R.id.tvTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_top10_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvRank.text  = (position + 1).toString()
            holder.tvTitle.text = item.name

            Glide.with(holder.itemView.context)
                .asBitmap()
                .load(item.streamIcon)
                .override(160, 240)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .dontAnimate()
                .placeholder(R.drawable.ic_launcher)
                .into(holder.ivPoster)

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX    = if (hasFocus) 1.08f else 1.0f
                v.scaleY    = if (hasFocus) 1.08f else 1.0f
                v.elevation = if (hasFocus) 12f else 0f
            }
        }

        override fun getItemCount() = list.size
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
