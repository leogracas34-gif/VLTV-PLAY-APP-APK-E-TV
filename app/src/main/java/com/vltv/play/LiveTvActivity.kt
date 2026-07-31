package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.Priority
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.nio.charset.Charset
import kotlin.math.abs

class LiveTvActivity : AppCompatActivity() {

    private lateinit var root: ConstraintLayout
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var rvEpgProgram: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCategoryTitle: TextView

    private lateinit var playerCard: View
    private lateinit var playerView: PlayerView
    private lateinit var playerLoading: ProgressBar
    private lateinit var tvPlayerChannelName: TextView
    private lateinit var playerMiniInfoBar: View
    private lateinit var expandedInfoBar: View
    private lateinit var tvExpandedChannelName: TextView
    private lateinit var tvExpandedNowPlaying: TextView

    private var username = ""
    private var password = ""

    private var cachedCategories: List<LiveCategory>? = null
    private val channelsCache = mutableMapOf<String, List<LiveStream>>()

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null
    private var epgAdapter: EpgProgramAdapter? = null

    // ── Player embutido (único, nunca recriado ao expandir/recolher) ──
    private var player: ExoPlayer? = null
    private var canalAtual: LiveStream? = null
    private var isExpanded = false
    private var ultimoEpgAgoraTexto: String = ""

    private var csMini: ConstraintSet = ConstraintSet()
    private var csExpanded: ConstraintSet = ConstraintSet()

    // ✅ Zoom = os mesmos 3 modos nativos que existiam no PlayerActivity
    // (Ajustar / Zoom / Preencher), agora acionados por gesto de pinça em
    // vez de um botão fixo. Diferente de escalar o PlayerView por fora
    // (o que cortava placar/logo/cronômetro), o resizeMode reformata o
    // FRAME INTEIRO do vídeo dentro da mesma caixa — nada baked na imagem
    // fica cortado de forma desproporcional.
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeModeNomes = listOf("Ajustar", "Zoom", "Preencher")
    // ✅ Padrão agora é PREENCHER (índice 2), tanto na mini tela quanto na
    // expandida (a expandida herda o modo da mini tela — só volta ao
    // padrão quando recolhe, não quando expande). Preencher estica o
    // vídeo pra ocupar 100% da caixa, sem manter a proporção original.
    private var resizeModeIndex = 2
    private var pinchAccum = 1f
    private val PINCH_THRESHOLD = 1.15f

    private val scaleGestureDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchAccum *= detector.scaleFactor
                if (pinchAccum >= PINCH_THRESHOLD) {
                    avancarModoZoom()
                    pinchAccum = 1f
                } else if (pinchAccum <= 1f / PINCH_THRESHOLD) {
                    voltarModoZoom()
                    pinchAccum = 1f
                }
                return true
            }
        })
    }

    // ✅ Toque simples (quando expandido) alterna o HUD com nome do canal
    // + programa atual.
    private val tapGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isExpanded) alternarHudExpandido()
                return true
            }
        })
    }

    // ✅ Controle manual de arrastar-para-baixo (swipe down) que recolhe
    // o player, igual ao gesto do YouTube/mini player.
    private var dragStartY = 0f
    private var isDragging = false
    private val DRAG_COLLAPSE_THRESHOLD_DP = 90

    // ✅ Tolerância de movimento pra diferenciar um toque real de um
    // arrasto/scroll que só passou por cima do player. Sem isso, QUALQUER
    // arrasto que termine em cima da mini tela (ex: rolando a lista de
    // canais do lado) contava como clique e expandia sem querer.
    private var miniDownX = 0f
    private var miniDownY = 0f
    private val TAP_SLOP_DP = 14f

    // Lista de failover de servidor
    private val serverBackupList = listOf(
        "http://fibercdn.sbs",
        "http://ranos.sbs",
        "http://cmdtv.casa",
        "http://cmdtv.pro",
        "http://cmdtv.sbs",
        "http://cmdtv.top",
        "http://cmdbr.life",
        "http://zeroum.pro",
        "http://shozcdn.site",
        "http://edgelow.site",
        "http://cdtune.site",
        "http://radiodiamond.site",
        "http://gort2.site"
    )
    private val activeServerList = mutableListOf<String>()
    private var serverIndex = 0

    private val extensoesTentativa = listOf("ts", "m3u8", "")
    private var extIndex = 0

    private val USER_AGENT = "IPTVSmartersPro"

    // ✅ Categoria virtual "Favoritos" — não existe na API do provedor,
    // é injetada localmente no topo da lista de categorias. Selecioná-la
    // não faz nenhuma chamada de rede: lê direto do FavoritesManager.
    private val FAVORITOS_CATEGORY_ID = "VLTV_FAVORITOS_LOCAL"

    // Detecção de TV centralizada em DeviceUtils.kt (isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv)

        // ✅ Mantém a tela acordada enquanto o usuário está assistindo,
        // mesmo sem tocar (a mini tela sozinha não gerava nenhuma
        // interação que resetasse o timer de bloqueio do Android).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        root                     = findViewById(R.id.root)
        rvCategories              = findViewById(R.id.rvCategories)
        rvChannels                = findViewById(R.id.rvChannels)
        rvEpgProgram              = findViewById(R.id.rvEpgProgram)
        progressBar               = findViewById(R.id.progressBar)
        tvCategoryTitle           = findViewById(R.id.tvCategoryTitle)

        playerCard                = findViewById(R.id.playerCard)
        playerView                = findViewById(R.id.playerView)
        playerLoading             = findViewById(R.id.playerLoading)
        tvPlayerChannelName       = findViewById(R.id.tvPlayerChannelName)
        playerMiniInfoBar         = findViewById(R.id.playerMiniInfoBar)
        expandedInfoBar           = findViewById(R.id.expandedInfoBar)
        tvExpandedChannelName     = findViewById(R.id.tvExpandedChannelName)
        tvExpandedNowPlaying      = findViewById(R.id.tvExpandedNowPlaying)

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        setupServerList()
        setupRecyclerFocus()
        setupConstraintSets()
        setupPlayerInteractions()

        rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvCategories.setHasFixedSize(true)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER
        rvCategories.isFocusable = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvChannels.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        rvChannels.isFocusable = true
        rvChannels.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvChannels.setHasFixedSize(true)

        rvEpgProgram.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)

        rvCategories.requestFocus()
        carregarCategorias()
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSTRAINT SETS — mini vs expandido
    // ═══════════════════════════════════════════════════════════════
    private fun setupConstraintSets() {
        csMini.clone(root)

        csExpanded.clone(root)
        csExpanded.clear(R.id.playerCard, ConstraintSet.START)
        csExpanded.clear(R.id.playerCard, ConstraintSet.END)
        csExpanded.clear(R.id.playerCard, ConstraintSet.TOP)
        csExpanded.clear(R.id.playerCard, ConstraintSet.BOTTOM)
        csExpanded.connect(R.id.playerCard, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        csExpanded.connect(R.id.playerCard, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        csExpanded.connect(R.id.playerCard, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        csExpanded.connect(R.id.playerCard, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        csExpanded.constrainWidth(R.id.playerCard, ConstraintSet.MATCH_CONSTRAINT)
        csExpanded.constrainHeight(R.id.playerCard, ConstraintSet.MATCH_CONSTRAINT)
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private fun setupPlayerInteractions() {
        // Toque no player: se estiver mini, expande. Gestos (pinça,
        // arrastar, toque) só são tratados de fato quando já expandido.
        playerView.setOnTouchListener { _, event ->
            if (!isExpanded) {
                // ✅ Modo mini: só conta como toque de expandir se o dedo
                // praticamente não se moveu entre o DOWN e o UP. Um
                // arrasto/swipe que termine em cima do player (rolando a
                // lista de canais, por exemplo) NÃO deve expandir.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        miniDownX = event.rawX
                        miniDownY = event.rawY
                    }
                    MotionEvent.ACTION_UP -> {
                        val slopPx = TAP_SLOP_DP * resources.displayMetrics.density
                        val moveu = abs(event.rawX - miniDownX) > slopPx || abs(event.rawY - miniDownY) > slopPx
                        if (!moveu) playerView.performClick()
                    }
                }
                return@setOnTouchListener false
            }

            scaleGestureDetector.onTouchEvent(event)
            tapGestureDetector.onTouchEvent(event)
            tratarArrastoParaFechar(event)
            true
        }

        playerView.setOnClickListener {
            if (!isExpanded) expandirPlayer()
        }
    }

    private fun tratarArrastoParaFechar(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleGestureDetector.isInProgress) {
                    val dy = event.rawY - dragStartY
                    if (dy > 20) isDragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val dy = event.rawY - dragStartY
                    val thresholdPx = DRAG_COLLAPSE_THRESHOLD_DP * resources.displayMetrics.density
                    if (dy > thresholdPx) recolherPlayer()
                }
                isDragging = false
            }
        }
    }

    private fun avancarModoZoom() {
        if (resizeModeIndex >= resizeModes.size - 1) return
        resizeModeIndex++
        aplicarModoZoom()
    }

    private fun voltarModoZoom() {
        if (resizeModeIndex <= 0) return
        resizeModeIndex--
        aplicarModoZoom()
    }

    private fun aplicarModoZoom() {
        playerView.resizeMode = resizeModes[resizeModeIndex]
        Toast.makeText(this, "Modo: ${resizeModeNomes[resizeModeIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun alternarHudExpandido() {
        expandedInfoBar.visibility = if (expandedInfoBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun expandirPlayer() {
        if (isExpanded) return
        isExpanded = true

        TransitionManager.beginDelayedTransition(root, ChangeBounds().setDuration(280))
        csExpanded.applyTo(root)

        sidebarViews().forEach { it.visibility = View.GONE }
        playerMiniInfoBar.visibility = View.GONE
    }

    private fun recolherPlayer() {
        if (!isExpanded) return
        isExpanded = false

        // Reseta zoom e HUD — a mini tela sempre volta no padrão
        // (Preencher, que ocupa 100% da caixa sem sobra preta).
        resizeModeIndex = 2
        pinchAccum = 1f
        playerView.resizeMode = resizeModes[resizeModeIndex]
        expandedInfoBar.visibility = View.GONE

        TransitionManager.beginDelayedTransition(root, ChangeBounds().setDuration(280))
        csMini.applyTo(root)

        sidebarViews().forEach { it.visibility = View.VISIBLE }
        playerMiniInfoBar.visibility = View.VISIBLE
    }

    private fun sidebarViews(): List<View> = listOf(
        findViewById(R.id.sidebarCategories),
        findViewById(R.id.columnChannels),
        findViewById(R.id.epgContainer)
    )

    // ═══════════════════════════════════════════════════════════════
    //  PLAYER — inicialização única, troca de canal só troca o
    //  MediaItem, nunca recria o ExoPlayer nem o PlayerView.
    // ═══════════════════════════════════════════════════════════════
    private fun setupServerList() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedDns = prefs.getString("dns", "") ?: ""

        activeServerList.clear()
        if (savedDns.isNotEmpty()) {
            activeServerList.add(savedDns.removeSuffix("/"))
        }
        for (server in serverBackupList) {
            val clean = server.removeSuffix("/")
            if (clean != savedDns && !savedDns.contains(clean)) {
                activeServerList.add(clean)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun garantirPlayerCriado() {
        if (player != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 5000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()

        playerView.player = player
        playerView.resizeMode = resizeModes[resizeModeIndex]

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY     -> playerLoading.visibility = View.GONE
                    Player.STATE_BUFFERING -> playerLoading.visibility = View.VISIBLE
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                extIndex++
                if (extIndex >= extensoesTentativa.size) {
                    extIndex = 0
                    serverIndex++
                }

                if (serverIndex < activeServerList.size) {
                    canalAtual?.let { tocarCanal(it, resetIndices = false) }
                } else {
                    serverIndex = 0
                    extIndex = 0
                    Toast.makeText(this@LiveTvActivity, "Falha ao conectar ao canal.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun tocarCanal(canal: LiveStream, resetIndices: Boolean = true) {
        garantirPlayerCriado()
        canalAtual = canal
        if (resetIndices) {
            serverIndex = 0
            extIndex = 0
        }

        if (activeServerList.isEmpty()) {
            Toast.makeText(this, "Erro: Sem servidor configurado.", Toast.LENGTH_LONG).show()
            return
        }
        if (serverIndex >= activeServerList.size) serverIndex = 0
        if (extIndex >= extensoesTentativa.size) extIndex = 0

        val server = activeServerList[serverIndex]
        val ext = extensoesTentativa[extIndex]
        val url = if (ext.isBlank())
            "$server/live/$username/$password/${canal.id}"
        else
            "$server/live/$username/$password/${canal.id}.$ext"

        playerLoading.visibility = View.VISIBLE
        tvPlayerChannelName.text = canal.name
        tvExpandedChannelName.text = canal.name
        tvExpandedNowPlaying.text = "Carregando programação..."

        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.playWhenReady = true
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar o canal.", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ Chamado ao clicar em um canal na lista — troca o MediaItem do
    // player já existente (nunca recria Activity/Surface) e atualiza o
    // destaque visual de "canal tocando agora" na lista.
    private fun selecionarCanal(canal: LiveStream) {
        tocarCanal(canal)
        carregarEpgDoPlayer(canal)
        channelAdapter?.marcarSelecionado(canal.id)
    }

    // ═══════════════════════════════════════════════════════════════
    //  CATEGORIAS / CANAIS
    // ═══════════════════════════════════════════════════════════════
    private fun preLoadChannelLogos(canais: List<LiveStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val limit = minOf(canais.size, 40)
            for (i in 0 until limit) {
                val url = canais[i].icon
                if (!url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Glide.with(this@LiveTvActivity)
                            .load(url)
                            .format(DecodeFormat.PREFER_ARGB_8888)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .priority(Priority.LOW)
                            .preload()
                    }
                }
            }
        }
    }

    private fun setupRecyclerFocus() {
        rvCategories.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) rvCategories.smoothScrollToPosition(0)
        }
        rvChannels.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) rvChannels.smoothScrollToPosition(0)
        }
    }

    private fun carregarCategorias() {
        cachedCategories?.let { aplicarCategorias(it); return }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getLiveCategories(username, password)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        try {
                            val rawJson = response.body()!!.string()
                            val lista = mutableListOf<LiveCategory>()
                            val gson = Gson()

                            if (rawJson.trim().startsWith("[")) {
                                val listType = object : TypeToken<List<LiveCategory>>() {}.type
                                lista.addAll(gson.fromJson(rawJson, listType))
                            } else if (rawJson.trim().startsWith("{")) {
                                val obj = JSONObject(rawJson)
                                val keys = obj.keys()
                                while (keys.hasNext()) {
                                    lista.add(gson.fromJson(obj.getJSONObject(keys.next()).toString(), LiveCategory::class.java))
                                }
                            }

                            cachedCategories = lista
                            aplicarCategorias(lista)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this@LiveTvActivity, "Erro no formato dos dados", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@LiveTvActivity, "Erro ao carregar categorias", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@LiveTvActivity, "Falha de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun aplicarCategorias(categoriasOriginais: List<LiveCategory>) {
        val categorias = if (ParentalControlManager.isEnabled(this))
            categoriasOriginais.filterNot { ParentalControlManager.isAdultName(it.name) }
        else categoriasOriginais

        if (categorias.isEmpty()) {
            Toast.makeText(this, "Nenhuma categoria disponível.", Toast.LENGTH_SHORT).show()
            rvCategories.adapter = CategoryAdapter(emptyList()) {}
            rvChannels.adapter = ChannelAdapter(emptyList(), isFavoritosView = false) {}
            return
        }

        // ✅ "Favoritos" entra sempre no topo da lista, injetada
        // localmente (não vem da API). O restante das categorias reais
        // segue depois dela, na ordem normal.
        val categoriaFavoritos = LiveCategory(FAVORITOS_CATEGORY_ID, "⭐ Favoritos")
        val listaCompleta = listOf(categoriaFavoritos) + categorias

        // selectedPos = 1 porque o índice 0 agora é "Favoritos" — o
        // comportamento padrão (abrir já na primeira categoria real)
        // continua o mesmo de antes.
        categoryAdapter = CategoryAdapter(listaCompleta, initialSelectedPos = 1) { carregarCanais(it) }
        rvCategories.adapter = categoryAdapter
        carregarCanais(categorias[0])
    }

    private fun carregarCanais(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name

        // ✅ Favoritos não vem da API — lê direto do armazenamento local,
        // instantâneo, sem loading nem chance de falha de conexão.
        if (categoria.id == FAVORITOS_CATEGORY_ID) {
            val favoritos = FavoritesManager.getFavorites(this)
            aplicarCanais(categoria, favoritos)
            return
        }

        val catIdStr = categoria.id.toString()

        channelsCache[catIdStr]?.let {
            aplicarCanais(categoria, it)
            return
        }

        progressBar.visibility = View.VISIBLE

        XtreamApi.service.getLiveStreams(username, password, categoryId = catIdStr)
            .enqueue(object : Callback<List<LiveStream>> {
                override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val canais = response.body()!!
                        channelsCache[catIdStr] = canais
                        aplicarCanais(categoria, canais)
                    } else {
                        Toast.makeText(this@LiveTvActivity, "Erro ao carregar canais", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@LiveTvActivity, "Falha de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun aplicarCanais(categoria: LiveCategory, canaisOriginais: List<LiveStream>) {
        tvCategoryTitle.text = categoria.name

        val canais = if (ParentalControlManager.isEnabled(this))
            canaisOriginais.filterNot { ParentalControlManager.isAdultName(it.name) }
        else canaisOriginais

        val ehFavoritos = categoria.id == FAVORITOS_CATEGORY_ID
        preLoadChannelLogos(canais)

        channelAdapter?.cancelarTodasChamadasEpg()
        channelAdapter = ChannelAdapter(canais, isFavoritosView = ehFavoritos) { canal ->
            // ✅ Clicar no canal que JÁ está tocando não recarrega o
            // stream — só expande a tela, já que o conteúdo é o mesmo.
            // Clicar em um canal diferente troca normalmente.
            if (canal.id == canalAtual?.id) {
                expandirPlayer()
            } else {
                selecionarCanal(canal)
            }
        }
        rvChannels.adapter = channelAdapter

        // ✅ Destaca em vermelho o canal atual nesta lista, SE ele
        // estiver presente aqui (ex: mesmo canal repetido em Favoritos).
        // Não muda o que está tocando, só reflete visualmente.
        channelAdapter?.marcarSelecionado(canalAtual?.id ?: -1)

        if (canais.isNotEmpty()) {
            // ✅ Só toca automaticamente o primeiro canal na PRIMEIRÍSSIMA
            // vez que a tela abre (quando ainda não há nada tocando).
            // Trocar de categoria depois disso NUNCA muda o canal que
            // está passando na mini tela — só quando o usuário clica em
            // um canal específico da lista.
            if (canalAtual == null) {
                selecionarCanal(canais[0])
            }
            rvChannels.post {
                rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        } else if (ehFavoritos) {
            Toast.makeText(this, "Você ainda não favoritou nenhum canal.", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EPG
    // ═══════════════════════════════════════════════════════════════
    private fun decodeBase64(text: String?): String {
        return try {
            if (text.isNullOrEmpty()) ""
            else String(Base64.decode(text, Base64.DEFAULT), Charset.forName("UTF-8"))
        } catch (e: Exception) { text ?: "" }
    }

    private fun carregarEpgDoPlayer(canal: LiveStream) {
        XtreamApi.service.getShortEpg(
            user = username, pass = password,
            streamId = canal.id.toString(), limit = 4
        ).enqueue(object : Callback<EpgWrapper> {
            override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                if (canalAtual?.id != canal.id) return
                val listagens = response.body()?.epg_listings
                if (!response.isSuccessful || listagens.isNullOrEmpty()) {
                    ultimoEpgAgoraTexto = "Sem informação de programação"
                    tvExpandedNowPlaying.text = ultimoEpgAgoraTexto
                    epgAdapter = EpgProgramAdapter(emptyList())
                    rvEpgProgram.adapter = epgAdapter
                    return
                }
                ultimoEpgAgoraTexto = decodeBase64(listagens[0].title)
                tvExpandedNowPlaying.text = ultimoEpgAgoraTexto
                epgAdapter = EpgProgramAdapter(listagens)
                rvEpgProgram.adapter = epgAdapter
            }
            override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {
                ultimoEpgAgoraTexto = "Falha ao carregar programação"
                tvExpandedNowPlaying.text = ultimoEpgAgoraTexto
                epgAdapter = EpgProgramAdapter(emptyList())
                rvEpgProgram.adapter = epgAdapter
            }
        })
    }

    inner class EpgProgramAdapter(
        private val list: List<EpgResponseItem>
    ) : RecyclerView.Adapter<EpgProgramAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvHorario: TextView = v.findViewById(R.id.tvEpgHorario)
            val tvTitulo: TextView = v.findViewById(R.id.tvEpgTitulo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_epg_program, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvHorario.text = item.start?.takeLast(5) ?: "--:--"
            holder.tvTitulo.text = decodeBase64(item.title)
        }

        override fun getItemCount() = list.size
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADAPTER CATEGORIAS
    // ═══════════════════════════════════════════════════════════════
    inner class CategoryAdapter(
        private val list: List<LiveCategory>,
        initialSelectedPos: Int = 0,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {

        private var selectedPos = initialSelectedPos.coerceIn(0, (list.size - 1).coerceAtLeast(0))

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val viewIndicator: View? = try { v.findViewById(R.id.viewIndicator) } catch (e: Exception) { null }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name
            atualizarEstilo(holder, position == selectedPos, false)

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true
            holder.itemView.isFocusableInTouchMode = this@LiveTvActivity.isTelevisionDevice()

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                atualizarEstilo(holder, selectedPos == position, hasFocus)
            }

            holder.itemView.setOnClickListener {
                notifyItemChanged(selectedPos)
                selectedPos = holder.adapterPosition
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        private fun atualizarEstilo(holder: VH, isSelected: Boolean, hasFocus: Boolean) {
            when {
                hasFocus -> {
                    holder.tvName.setTextColor(Color.YELLOW)
                    holder.tvName.textSize = 13f
                    holder.itemView.setBackgroundResource(R.drawable.bg_focus_neon)
                    holder.itemView.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start()
                    holder.viewIndicator?.visibility = View.VISIBLE
                }
                isSelected -> {
                    holder.tvName.setTextColor(Color.WHITE)
                    holder.tvName.textSize = 12f
                    holder.itemView.setBackgroundColor(0x22FFFFFF)
                    holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    holder.viewIndicator?.visibility = View.VISIBLE
                }
                else -> {
                    holder.tvName.setTextColor(0x88FFFFFF.toInt())
                    holder.tvName.textSize = 12f
                    holder.itemView.setBackgroundColor(0x00000000)
                    holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    holder.viewIndicator?.visibility = View.INVISIBLE
                }
            }
        }

        override fun getItemCount() = list.size
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADAPTER CANAIS — agora destaca visualmente qual canal está
    //  tocando no player (fundo + texto em vermelho), pra ficar claro
    //  qual item da lista corresponde ao que está no mini player.
    // ═══════════════════════════════════════════════════════════════
    inner class ChannelAdapter(
        initialList: List<LiveStream>,
        private val isFavoritosView: Boolean = false,
        private val onClick: (LiveStream) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        // ✅ Lista mutável — necessário pra remover um item na hora (sem
        // esperar recarregar a categoria) quando o usuário desfavorita um
        // canal estando DENTRO da aba Favoritos.
        private val list = initialList.toMutableList()

        private val epgCache = mutableMapOf<Int, List<EpgResponseItem>>()
        private val epgCallsAtivas = mutableMapOf<Int, Call<EpgWrapper>>()
        private val zoomFocus = if (this@LiveTvActivity.isTelevisionDevice()) 1.06f else 1.03f
        private var selectedChannelId: Int = -1

        fun marcarSelecionado(channelId: Int) {
            if (selectedChannelId == channelId) return
            selectedChannelId = channelId
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvNow: TextView = v.findViewById(R.id.tvNow)
            val tvNext: TextView = v.findViewById(R.id.tvNext)
            val imgLogo: ImageView = v.findViewById(R.id.imgLogo)
            val imgFavorite: ImageView = v.findViewById(R.id.imgFavorite)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            val estaSelecionado = item.id == selectedChannelId
            holder.itemView.setBackgroundColor(if (estaSelecionado) 0x33E50914 else 0x00000000)
            holder.tvName.setTextColor(if (estaSelecionado) Color.parseColor("#FF4D4D") else Color.WHITE)

            // ✅ Estrela: contorno se não favoritado, preenchida e
            // amarela se favoritado. Toque aqui não seleciona o canal —
            // só alterna o favorito, tratado separadamente do clique
            // na linha inteira.
            atualizarIconeFavorito(holder, item)
            holder.imgFavorite.setOnClickListener {
                val agoraFavoritado = FavoritesManager.toggleFavorite(this@LiveTvActivity, item)
                atualizarIconeFavorito(holder, item)

                // Na aba Favoritos, desfavoritar remove o item na hora.
                if (isFavoritosView && !agoraFavoritado) {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        list.removeAt(pos)
                        notifyItemRemoved(pos)
                    }
                }
            }

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .placeholder(R.drawable.bg_logo_placeholder)
                .error(R.drawable.bg_logo_placeholder)
                .fitCenter()
                .into(holder.imgLogo)

            holder.tvNow.text = "Carregando..."
            holder.tvNext.text = ""

            carregarEpg(holder, item, position)

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true
            holder.itemView.isFocusableInTouchMode = this@LiveTvActivity.isTelevisionDevice()

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    holder.tvName.setTextColor(Color.YELLOW)
                    view.setBackgroundResource(R.drawable.bg_focus_neon)
                    view.animate().scaleX(zoomFocus).scaleY(zoomFocus).setDuration(160).start()
                    view.elevation = 16f
                } else {
                    holder.tvName.setTextColor(if (item.id == selectedChannelId) Color.parseColor("#FF4D4D") else Color.WHITE)
                    view.setBackgroundColor(if (item.id == selectedChannelId) 0x33E50914 else 0x00000000)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()
                    view.elevation = 4f
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        private fun atualizarIconeFavorito(holder: VH, item: LiveStream) {
            val favoritado = FavoritesManager.isFavorite(this@LiveTvActivity, item.id)
            holder.imgFavorite.setImageResource(
                if (favoritado) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                epgCallsAtivas.remove(pos)?.cancel()
            }
        }

        fun cancelarTodasChamadasEpg() {
            epgCallsAtivas.values.forEach { it.cancel() }
            epgCallsAtivas.clear()
        }

        private fun decodeBase64Local(text: String?): String {
            return try {
                if (text.isNullOrEmpty()) ""
                else String(Base64.decode(text, Base64.DEFAULT), Charset.forName("UTF-8"))
            } catch (e: Exception) { text ?: "" }
        }

        private fun carregarEpg(holder: VH, canal: LiveStream, position: Int) {
            epgCache[canal.id]?.let { mostrarEpg(holder, it); return }

            epgCallsAtivas[position]?.cancel()

            val call = XtreamApi.service.getShortEpg(
                user = username,
                pass = password,
                streamId = canal.id.toString(),
                limit = 2
            )
            epgCallsAtivas[position] = call

            call.enqueue(object : Callback<EpgWrapper> {
                override fun onResponse(call: Call<EpgWrapper>, response: Response<EpgWrapper>) {
                    epgCallsAtivas.remove(position)
                    if (holder.adapterPosition != position) return
                    if (response.isSuccessful && response.body()?.epg_listings != null) {
                        val epg = response.body()!!.epg_listings!!
                        epgCache[canal.id] = epg
                        mostrarEpg(holder, epg)
                    } else {
                        holder.tvNow.text = ""
                        holder.tvNext.text = ""
                    }
                }
                override fun onFailure(call: Call<EpgWrapper>, t: Throwable) {
                    epgCallsAtivas.remove(position)
                    if (holder.adapterPosition != position) return
                    holder.tvNow.text = ""
                    holder.tvNext.text = ""
                }
            })
        }

        private fun mostrarEpg(holder: VH, epg: List<EpgResponseItem>) {
            if (epg.isNotEmpty()) {
                holder.tvNow.text = decodeBase64Local(epg[0].title)
                holder.tvNext.text = if (epg.size > 1) decodeBase64Local(epg[1].title) else ""
            } else {
                holder.tvNow.text = ""
                holder.tvNext.text = ""
            }
        }

        override fun getItemCount() = list.size
    }

    // ═══════════════════════════════════════════════════════════════
    //  VOLTAR — recolhe se expandido, sai da tela se já estiver mini.
    // ═══════════════════════════════════════════════════════════════
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isExpanded) recolherPlayer() else finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
