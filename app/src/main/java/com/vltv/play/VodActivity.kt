package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.CategoryEntity
import com.vltv.play.data.VodEntity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class VodActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView
    private var username = ""
    private var password = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var gridCachePrefs: SharedPreferences

    // Cache em memória da sessão — evita bater na rede duas vezes para a mesma categoria
    private val moviesCache = mutableMapOf<String, List<VodStream>>()
    private var categoryAdapter: VodCategoryAdapter? = null

    // Adapter único — nunca recriado, atualizado via DiffUtil
    private var moviesAdapter: VodAdapter? = null

    private val logoMemoryCache = mutableMapOf<String, String>()
    private var ultimaCategoriaId: String? = null
    private var ultimaCategoriaNome: String? = null

    // Guard de race condition
    private var categoriaAtualId: String? = null

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var bottomNavigation: BottomNavigationView? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    // Detecção de TV centralizada em DeviceUtils.kt (context.isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    // ✅ Filtro central de conteúdo adulto para FILMES — chamado em TODO ponto
    // onde uma lista vai pro adapter, não importa de onde os dados vieram
    // (cache em memória, ContentRepository, banco Room, rede ou favoritos).
    private fun filtrarFilmesAdultos(lista: List<VodStream>): List<VodStream> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) || ParentalControlManager.isAdultName(it.title) }
        else lista
    }

    // ✅ Filtro central de conteúdo adulto para CATEGORIAS
    private fun filtrarCategoriasAdultas(lista: List<LiveCategory>): List<LiveCategory> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (this.isTelevisionDevice()) {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }

        rvCategories    = findViewById(R.id.rvCategories)
        rvMovies        = findViewById(R.id.rvChannels)
        progressBar     = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        gridCachePrefs  = getSharedPreferences("vltv_grid_cache", Context.MODE_PRIVATE)

        setupBottomNavigation()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        findViewById<View>(R.id.etSearchContent)?.apply {
            isFocusableInTouchMode = false
            setOnClickListener {
                startActivity(Intent(this@VodActivity, SearchActivity::class.java).apply {
                    putExtra("initial_query", "")
                    putExtra("PROFILE_NAME", currentProfile)
                    putExtra("tipo_pesquisa", "filmes")
                })
            }
        }

        prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        if (this.isTelevisionDevice()) {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            rvMovies.layoutManager     = GridLayoutManager(this, 5)
            bottomNavigation?.visibility = View.GONE
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rvMovies.layoutManager     = GridLayoutManager(this, 3)
        }

        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER

        if (this.isTelevisionDevice()) {
            rvCategories.isFocusable = true
            rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rvMovies.isFocusable = true
            rvMovies.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            rvCategories.isFocusable = false
            rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rvMovies.isFocusable = false
            rvMovies.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        rvMovies.setHasFixedSize(true)
        rvMovies.setItemViewCacheSize(100)

        // Adapter criado UMA vez — nunca recriado
        moviesAdapter = VodAdapter(
            onItemClick     = { abrirDetalhes(it) },
            onDownloadClick = { mostrarMenuDownload(it) }
        )
        rvMovies.adapter = moviesAdapter

        // Última categoria salva
        val catPrefs = getSharedPreferences("vltv_vod_prefs", Context.MODE_PRIVATE)
        ultimaCategoriaId   = catPrefs.getString("ultima_cat_id", null)
        ultimaCategoriaNome = catPrefs.getString("ultima_cat_nome", null)

        // ── CARREGAMENTO INSTANTÂNEO DE FILMES ───────────────────────────────
        // ContentRepository.getVodsByCategory() = O(1), retorna em < 1ms.
        val catId = ultimaCategoriaId
        if (catId != null) {
            val filmesEmMemoria = ContentRepository.getVodsByCategory(catId)
            if (filmesEmMemoria.isNotEmpty()) {
                categoriaAtualId = catId
                if (ultimaCategoriaNome != null) tvCategoryTitle.text = ultimaCategoriaNome
                filmesEmMemoria.take(30).forEach { vod ->
                    val cached = gridCachePrefs.getString("logo_${vod.name}", null)
                    if (cached != null) logoMemoryCache[vod.name] = cached
                }
                val items = filmesEmMemoria.map {
                    VodStream(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating)
                }
                // ✅ Filtro aplicado também no carregamento instantâneo
                val itemsFiltrados = filtrarFilmesAdultos(items)
                moviesAdapter?.submitList(itemsFiltrados)
                preLoadImages(itemsFiltrados)
            }
        }

        // ── CARREGAMENTO INSTANTÂNEO DE CATEGORIAS ───────────────────────────
        // Lê do banco Room (thread IO, ~2ms) → mostra imediatamente.
        // A rede atualiza em background e só reaplica se algo mudou.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categoriasSalvas = database.streamDao().getCategoriesByType("vod")
                if (categoriasSalvas.isNotEmpty()) {
                    val cats = mutableListOf<LiveCategory>()
                    cats.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                    cats.addAll(categoriasSalvas.map {
                        LiveCategory(category_id = it.category_id, category_name = it.category_name)
                    })
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) aplicarCategorias(cats)
                    }
                }
                // Sempre busca da rede em background para manter atualizado
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) carregarCategoriasRede()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) carregarCategoriasRede()
                }
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home      -> { finish(); true }
                R.id.nav_search    -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_novidades -> {
                    startActivity(Intent(this, NovidadesActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_profile   -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = vltvPrefs.getString("last_profile_name", currentProfile) ?: currentProfile
        currentProfileIcon = vltvPrefs.getString("last_profile_icon", currentProfileIcon)
            ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
    }

    // ✅ Corrigido: usa lifecycleScope em vez de CoroutineScope(Dispatchers.IO) solta.
    // Assim a coroutine é cancelada automaticamente quando a Activity é destruída,
    // evitando "You cannot start a load for a destroyed activity".
    private fun preLoadImages(filmes: List<VodStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            filmes.take(30).forEach { vod ->
                val url = vod.icon ?: return@forEach
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Glide.with(this@VodActivity)
                        .asBitmap().load(url)
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.HIGH)
                        .preload(240, 360)
                }
            }
        }
    }

    private suspend fun searchTmdbLogoVod(rawName: String): String? {
        val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
        val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
        val year = yearRegex.find(rawName)?.value
        val cleanName = rawName
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(yearRegex, "").trim()
        return try {
            var url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey" +
                    "&query=${URLEncoder.encode(cleanName, "UTF-8")}&language=pt-BR&region=BR&include_adult=false"
            if (year != null) url += "&year=$year"
            val results = JSONObject(URL(url).readText()).getJSONArray("results")
            if (results.length() == 0) return null
            val id = results.getJSONObject(0).getString("id")
            val logos = JSONObject(
                URL("https://api.themoviedb.org/3/movie/$id/images?api_key=$apiKey&include_image_language=pt,en,null")
                    .readText()
            ).getJSONArray("logos")
            if (logos.length() == 0) return null
            var path: String? = null
            for (i in 0 until logos.length()) {
                if (logos.getJSONObject(i).optString("iso_639_1") == "pt") {
                    path = logos.getJSONObject(i).getString("file_path"); break
                }
            }
            if (path == null) path = logos.getJSONObject(0).getString("file_path")
            "https://cdn.vltvplay.tech/t/p/w500$path"
        } catch (e: Exception) { null }
    }

    /**
     * Busca categorias da REDE em background.
     * Só reaplica na tela se a lista mudou em relação ao que já está exibido.
     * Salva no banco para a próxima abertura ser instantânea.
     */
    private fun carregarCategoriasRede() {
        XtreamApi.service.getVodCategories(username, password)
            .enqueue(object : retrofit2.Callback<ResponseBody> {
                override fun onResponse(
                    call: retrofit2.Call<ResponseBody>,
                    response: retrofit2.Response<ResponseBody>
                ) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val lista = mutableListOf<LiveCategory>()
                        val gson = Gson()
                        if (rawJson.trim().startsWith("[")) {
                            val type = object : TypeToken<List<LiveCategory>>() {}.type
                            lista.addAll(gson.fromJson(rawJson, type))
                        } else if (rawJson.trim().startsWith("{")) {
                            val obj = JSONObject(rawJson)
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                lista.add(gson.fromJson(obj.getJSONObject(keys.next()).toString(), LiveCategory::class.java))
                            }
                        }

                        // Salva no banco em background (próxima abertura será instantânea)
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val entities = lista.map {
                                    CategoryEntity(it.category_id, it.category_name, "vod")
                                }
                                database.streamDao().deleteCategoriesByType("vod")
                                database.streamDao().insertCategories(entities)
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        // ✅ Lista crua aqui — o filtro é aplicado dentro de
                        // aplicarCategorias(), centralizando a regra num único lugar.
                        val cats = mutableListOf<LiveCategory>()
                        cats.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                        cats.addAll(lista)

                        // Só reaplica se o adapter ainda não tem categorias
                        // (evita piscar quando o banco já carregou)
                        if (categoryAdapter == null) {
                            aplicarCategorias(cats)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: retrofit2.Call<ResponseBody>, t: Throwable) {}
            })
    }

    private fun aplicarCategorias(categoriasOriginais: List<LiveCategory>) {
        if (isFinishing || isDestroyed) return

        // ✅ Filtro central de conteúdo adulto. Roda sempre, não importa se a
        // lista veio do banco Room (carregamento instantâneo) ou da rede.
        val categorias = filtrarCategoriasAdultas(categoriasOriginais)

        val catSalvaId = ultimaCategoriaId
        val indexInicial = if (catSalvaId != null) {
            val idx = categorias.indexOfFirst { it.id == catSalvaId }
            if (idx >= 0) idx else if (categorias.size > 1) 1 else 0
        } else {
            if (categorias.size > 1) 1 else 0
        }

        categoryAdapter = VodCategoryAdapter(categorias, indexInicial) { categoria ->
            salvarUltimaCategoria(categoria)
            if (categoria.id == "FAV") carregarFilmesFavoritos()
            else carregarFilmes(categoria)
        }
        rvCategories.adapter = categoryAdapter

        val categoriaAlvo = categorias.getOrNull(indexInicial)
            ?.takeIf { it.id != "FAV" }
            ?: categorias.firstOrNull { it.id != "FAV" }

        if (categoriaAlvo != null) {
            tvCategoryTitle.text = categoriaAlvo.name
            if (categoriaAlvo.id == categoriaAtualId) {
                atualizarEmBackground(categoriaAlvo)
            } else {
                carregarFilmes(categoriaAlvo)
            }
        }
    }

    private fun salvarUltimaCategoria(categoria: LiveCategory) {
        ultimaCategoriaId   = categoria.id
        ultimaCategoriaNome = categoria.name
        getSharedPreferences("vltv_vod_prefs", Context.MODE_PRIVATE).edit()
            .putString("ultima_cat_id", categoria.id)
            .putString("ultima_cat_nome", categoria.name)
            .apply()
    }

    private fun atualizarEmBackground(categoria: LiveCategory) {
        if (moviesCache.containsKey(categoria.id)) return
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    if (!response.isSuccessful || response.body() == null) return
                    val filmes = response.body()!!
                    // ✅ Cache guarda a lista crua — filtro aplicado só no submit
                    moviesCache[categoria.id] = filmes
                    if (categoriaAtualId == categoria.id) {
                        moviesAdapter?.submitList(filtrarFilmesAdultos(filmes))
                    }
                    salvarNoBancoERepositorio(categoria.id, filmes)
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {}
            })
    }

    private fun carregarFilmes(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        categoriaAtualId = categoria.id
        salvarUltimaCategoria(categoria)

        // 1. Cache de memória da API — instantâneo
        moviesCache[categoria.id]?.let {
            val filtrados = filtrarFilmesAdultos(it)
            moviesAdapter?.submitList(filtrados); preLoadImages(filtrados); return
        }

        // 2. ContentRepository — O(1), instantâneo
        val emRepositorio = ContentRepository.getVodsByCategory(categoria.id)
        if (emRepositorio.isNotEmpty()) {
            emRepositorio.take(30).forEach { vod ->
                val cached = gridCachePrefs.getString("logo_${vod.name}", null)
                if (cached != null) logoMemoryCache[vod.name] = cached
            }
            val items = emRepositorio.map {
                VodStream(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating)
            }
            val itemsFiltrados = filtrarFilmesAdultos(items)
            moviesAdapter?.submitList(itemsFiltrados)
            preLoadImages(itemsFiltrados)
            atualizarEmBackground(categoria)
            return
        }

        // 3. Sem dados locais — primeira instalação
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val filmes = response.body()!!
                    moviesCache[categoria.id] = filmes
                    if (categoriaAtualId == categoria.id) {
                        val filtrados = filtrarFilmesAdultos(filmes)
                        moviesAdapter?.submitList(filtrados)
                        preLoadImages(filtrados)
                    }
                    salvarNoBancoERepositorio(categoria.id, filmes)
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun salvarNoBancoERepositorio(categoryId: String, filmes: List<VodStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entities = filmes.map {
                    VodEntity(it.stream_id, it.name, it.title, it.stream_icon,
                        it.container_extension, it.rating, categoryId, System.currentTimeMillis())
                }
                database.streamDao().insertVodStreams(entities)
                ContentRepository.atualizarCategoriaVod(categoryId, entities)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun carregarFilmesFavoritos() {
        categoriaAtualId = "FAV"
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavMovies(this)
        if (favIds.isEmpty()) { moviesAdapter?.submitList(emptyList()); return }
        val listaNoCache = moviesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        if (listaNoCache.size >= favIds.size) {
            moviesAdapter?.submitList(filtrarFilmesAdultos(listaNoCache)); return
        }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = "0")
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val todos = response.body()!!
                    moviesCache["ALL_FOR_FAV"] = todos
                    val favs = todos.filter { favIds.contains(it.id) }
                    if (categoriaAtualId == "FAV") {
                        val favsFiltrados = filtrarFilmesAdultos(favs)
                        moviesAdapter?.submitList(favsFiltrados)
                        preLoadImages(favsFiltrados)
                    }
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    if (categoriaAtualId == "FAV") moviesAdapter?.submitList(filtrarFilmesAdultos(listaNoCache))
                }
            })
    }

    private fun abrirDetalhes(filme: VodStream) {
        startActivity(Intent(this, DetailsActivity::class.java).apply {
            putExtra("stream_id", filme.id)
            putExtra("name", filme.name)
            putExtra("icon", filme.icon)
            putExtra("rating", filme.rating ?: "0.0")
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("PROFILE_ICON", currentProfileIcon)
        })
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        val p = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        return p.getStringSet("${currentProfile}_favoritos", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
    }

    private fun mostrarMenuDownload(filme: VodStream) {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        menuInflater.inflate(R.menu.menu_download, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_download)
                Toast.makeText(this, "Baixando: ${filme.name}", Toast.LENGTH_LONG).show()
            true
        }
        popup.show()
    }

    // =========================================================================
    // ADAPTER DE CATEGORIAS
    // =========================================================================
    inner class VodCategoryAdapter(
        private val list: List<LiveCategory>,
        initialSelectedPos: Int = 0,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {

        private var selectedPos = initialSelectedPos

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false))

        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            h.tvName.text = item.name
            val isSel = selectedPos == p
            h.tvName.setTextColor(getColor(if (isSel) R.color.red_primary else R.color.gray_text))
            h.tvName.setBackgroundColor(if (isSel) 0xFF252525.toInt() else 0x00000000)
            h.itemView.isFocusable = this@VodActivity.isTelevisionDevice()
            h.itemView.setOnClickListener {
                val oldPos = selectedPos
                selectedPos = h.adapterPosition
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    // =========================================================================
    // ADAPTER DE FILMES — DiffUtil, sem placeholder, sem círculo
    // =========================================================================
    inner class VodAdapter(
        private val onItemClick: (VodStream) -> Unit,
        private val onDownloadClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<VodAdapter.VH>() {

        private val items = mutableListOf<VodStream>()

        fun submitList(newList: List<VodStream>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = newList.size
                override fun areItemsTheSame(o: Int, n: Int) = items[o].id == newList[n].id
                override fun areContentsTheSame(o: Int, n: Int) =
                    items[o].name == newList[n].name && items[o].icon == newList[n].icon
            })
            items.clear()
            items.addAll(newList)
            diff.dispatchUpdatesTo(this)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView     = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView   = v.findViewById(R.id.imgLogo)
            var job: Job? = null
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_vod, p, false))

        override fun onBindViewHolder(h: VH, p: Int) {
            h.job?.cancel()
            val item = items[p]

            h.tvName.text = item.name
            h.tvName.visibility  = View.VISIBLE
            h.imgLogo.setImageDrawable(null)
            h.imgLogo.visibility = View.INVISIBLE

            Glide.with(h.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(240, 360)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .centerCrop()
                .into(h.imgPoster)

            val memCached = logoMemoryCache[item.name]
            if (memCached != null) {
                h.tvName.visibility  = View.GONE
                h.imgLogo.visibility = View.VISIBLE
                Glide.with(h.itemView.context).load(memCached)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(h.imgLogo)
            } else {
                val diskCached = gridCachePrefs.getString("logo_${item.name}", null)
                if (diskCached != null) {
                    logoMemoryCache[item.name] = diskCached
                    h.tvName.visibility  = View.GONE
                    h.imgLogo.visibility = View.VISIBLE
                    Glide.with(h.itemView.context).load(diskCached)
                        .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(h.imgLogo)
                } else {
                    // ✅ Corrigido: lifecycleScope em vez de CoroutineScope(Dispatchers.IO)
                    // solta. Isso cancela automaticamente a busca de logo se a Activity
                    // for destruída, evitando o crash "destroyed activity" no Glide.with().
                    h.job = lifecycleScope.launch(Dispatchers.IO) {
                        val url = searchTmdbLogoVod(item.name)
                        if (url != null) {
                            logoMemoryCache[item.name] = url
                            gridCachePrefs.edit().putString("logo_${item.name}", url).apply()
                            withContext(Dispatchers.Main) {
                                // ✅ Guard extra: nunca chama Glide se a Activity já
                                // estiver finalizando/destruída (ex: usuário saiu da tela
                                // enquanto a busca TMDB ainda estava em andamento).
                                if (isFinishing || isDestroyed) return@withContext
                                if (h.adapterPosition == p) {
                                    h.tvName.visibility  = View.GONE
                                    h.imgLogo.visibility = View.VISIBLE
                                    Glide.with(h.itemView.context).load(url)
                                        .override(200, 110)
                                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                                        .dontAnimate().into(h.imgLogo)
                                }
                            }
                        }
                    }
                }
            }

            h.itemView.isFocusable = this@VodActivity.isTelevisionDevice()
            h.itemView.isClickable = true
            h.itemView.setOnClickListener { onItemClick(item) }

            if (this@VodActivity.isTelevisionDevice()) {
                h.itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(16f).setDuration(180).start()
                        v.findViewById<View>(R.id.viewFocusBorder)?.visibility = View.VISIBLE
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(180).start()
                        v.findViewById<View>(R.id.viewFocusBorder)?.visibility = View.INVISIBLE
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
