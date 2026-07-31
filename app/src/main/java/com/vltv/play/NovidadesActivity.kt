package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NovidadesActivity : AppCompatActivity() {

    private lateinit var tabEmBreve: TextView
    private lateinit var tabTodoMundo: TextView
    private lateinit var tabTopSeries: TextView
    private lateinit var tabTopFilmes: TextView
    private lateinit var recyclerNovidades: RecyclerView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var adapter: NovidadesAdapter

    private val listaEmBreve   = mutableListOf<NovidadeItem>()
    private val listaTodoMundo = mutableListOf<NovidadeItem>()
    private val listaTopSeries = mutableListOf<NovidadeItem>()
    private val listaTopFilmes = mutableListOf<NovidadeItem>()

    private val apiKey = "9b73f5dd15b8165b1b57419be2f29128"

    // ── OkHttpClient compartilhado com timeouts curtos ────────────────────────
    // CORREÇÃO: timeout de 8s → 5s; pool de conexões reutilizado entre chamadas
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    private var currentProfile = "Padrao"
    private var currentProfileIcon: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    // ── Abas carregadas: evita refetch ao trocar de aba ──────────────────────
    private val abasCarregadas = mutableSetOf<String>()

    // ── Mapas de banco carregados UMA VEZ, passados ao adapter ───────────────
    private var vodsMap: Map<String, VodEntity> = emptyMap()
    private var seriesMap: Map<String, SeriesEntity> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novidades)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        tabEmBreve        = findViewById(R.id.tabEmBreve)
        tabTodoMundo      = findViewById(R.id.tabBombando)
        tabTopSeries      = findViewById(R.id.tabTopSeries)
        tabTopFilmes      = findViewById(R.id.tabTopFilmes)
        recyclerNovidades = findViewById(R.id.recyclerNovidades)
        bottomNavigation  = findViewById(R.id.bottomNavigation)

        adapter = NovidadesAdapter(emptyList(), currentProfile, database, emptyMap(), emptyMap())
        recyclerNovidades.layoutManager = LinearLayoutManager(this)

        // ── Otimizações do RecyclerView ───────────────────────────────────────
        recyclerNovidades.setHasFixedSize(true)
        recyclerNovidades.setItemViewCacheSize(6)           // mantém 6 holders fora da tela
        recyclerNovidades.recycledViewPool.setMaxRecycledViews(0, 8)
        recyclerNovidades.adapter = adapter

        configurarAbas()
        configurarRodape()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        // CORREÇÃO: banco e TMDB em paralelo com async — o que chegar primeiro já aparece
        CoroutineScope(Dispatchers.Main).launch {
            // Banco e TMDB rodam simultaneamente
            val bancoDeferido = async(Dispatchers.IO) {
                val todasVods   = database.streamDao().getAllVods()
                val todasSeries = database.streamDao().getAllSeries()
                Pair(
                    todasVods.associateBy   { normalizarNomeBanco(it.name) },
                    todasSeries.associateBy { normalizarNomeBanco(it.name) }
                )
            }

            // TMDB dispara imediatamente (não bloqueia o async do banco)
            carregarTudo()

            // Quando banco terminar, atualiza mapas
            val (vMap, sMap) = bancoDeferido.await()
            vodsMap   = vMap
            seriesMap = sMap
            adapter.atualizarMapas(vMap, sMap)
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

    private fun configurarRodape() {
        bottomNavigation.selectedItemId = R.id.nav_novidades
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                    finish(); true
                }
                R.id.nav_novidades -> true
                R.id.nav_profile -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                    finish(); true
                }
                else -> false
            }
        }
    }

    private fun configurarAbas() {
        ativarAba(tabEmBreve)

        tabEmBreve.setOnClickListener {
            ativarAba(tabEmBreve)
            adapter.atualizarLista(listaEmBreve)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTodoMundo.setOnClickListener {
            ativarAba(tabTodoMundo)
            adapter.atualizarLista(listaTodoMundo)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTopSeries.setOnClickListener {
            ativarAba(tabTopSeries)
            adapter.atualizarLista(listaTopSeries)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTopFilmes.setOnClickListener {
            ativarAba(tabTopFilmes)
            adapter.atualizarLista(listaTopFilmes)
            recyclerNovidades.scrollToPosition(0)
        }
    }

    private fun ativarAba(aba: TextView) {
        listOf(tabEmBreve, tabTodoMundo, tabTopSeries, tabTopFilmes).forEach {
            if (it == aba) {
                it.setBackgroundResource(R.drawable.bg_aba_selecionada)
                it.setTextColor(Color.BLACK)
            } else {
                it.setBackgroundResource(R.drawable.bg_aba_inativa)
                it.setTextColor(Color.WHITE)
            }
        }
    }

    private fun carregarTudo() {
        // ── Em Breve ──────────────────────────────────────────────────────────
        // CORREÇÃO: removido region=BR que causava latência extra no TMDB
        // Agora filtra por data no cliente: só exibe filmes com release_date > hoje
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/movie/upcoming" +
                           "?api_key=$apiKey&language=pt-BR&page=1",
            destino      = listaEmBreve,
            isTop10      = false,
            isEmBreve    = true,
            isSerie      = false,
            tagFixa      = "Estreia em Breve",
            usarPoster   = true,
            limite       = 20,
            detectarTipo = false
        ) {
            runOnUiThread { adapter.atualizarLista(listaEmBreve) }
        }

        // ── Bombando — trending da semana ─────────────────────────────────────
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/trending/all/week" +
                           "?api_key=$apiKey&language=pt-BR",
            destino      = listaTodoMundo,
            isTop10      = false,
            isEmBreve    = false,
            isSerie      = false,
            tagFixa      = "Bombando no Mundo",
            usarPoster   = false,
            limite       = 20,
            detectarTipo = true
        ) {}

        // ── Top 10 Séries ──────────────────────────────────────────────────────
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/tv/popular" +
                           "?api_key=$apiKey&language=pt-BR&page=1",
            destino      = listaTopSeries,
            isTop10      = true,
            isEmBreve    = false,
            isSerie      = true,
            tagFixa      = "Top 10 Séries",
            usarPoster   = false,
            limite       = 10,
            detectarTipo = false
        ) {}

        // ── Top 10 Filmes ──────────────────────────────────────────────────────
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/movie/popular" +
                           "?api_key=$apiKey&language=pt-BR&page=1",
            destino      = listaTopFilmes,
            isTop10      = true,
            isEmBreve    = false,
            isSerie      = false,
            tagFixa      = "Top 10 Filmes",
            usarPoster   = false,
            limite       = 10,
            detectarTipo = false
        ) {}
    }

    private fun buscarTMDB(
        url: String,
        destino: MutableList<NovidadeItem>,
        isTop10: Boolean,
        isEmBreve: Boolean,
        isSerie: Boolean,
        tagFixa: String,
        usarPoster: Boolean,
        limite: Int,
        detectarTipo: Boolean,
        onSucesso: () -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body    = response.body?.string() ?: return
                    val results = JSONObject(body).optJSONArray("results") ?: return
                    val temp    = mutableListOf<NovidadeItem>()
                    var posicao = 1

                    // ── Data de hoje para filtrar "Em Breve" no cliente ────────
                    val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    for (i in 0 until results.length()) {
                        if (temp.size >= limite) break
                        val obj = results.getJSONObject(i)

                        val tipoDetectado = if (detectarTipo)
                            obj.optString("media_type") == "tv"
                        else
                            isSerie

                        if (detectarTipo && obj.optString("media_type") == "person") continue

                        val titulo = obj.optString("title", obj.optString("name", ""))
                        if (titulo.isEmpty()) continue

                        // CORREÇÃO: "Em Breve" só mostra filmes com data futura
                        val releaseDate = obj.optString("release_date",
                                              obj.optString("first_air_date", ""))
                        if (isEmBreve && releaseDate.isNotEmpty() && releaseDate <= hoje) continue

                        val pathImagem = if (usarPoster)
                            obj.optString("poster_path", "")
                        else
                            obj.optString("backdrop_path", obj.optString("poster_path", ""))
                        if (pathImagem.isEmpty()) continue

                        val sinopse  = obj.optString("overview", "Descrição indisponível.")
                        val tagFinal = if (isEmBreve && releaseDate.isNotEmpty())
                            formatarData(releaseDate) else tagFixa

                        temp.add(NovidadeItem(
                            idTMDB         = obj.optInt("id"),
                            titulo         = titulo,
                            sinopse        = sinopse,
                            imagemFundoUrl = "https://cdn.vltvplay.tech/t/p/w780$pathImagem",
                            tagline        = tagFinal,
                            isSerie        = tipoDetectado,
                            isEmBreve      = isEmBreve,
                            isTop10        = isTop10,
                            posicaoTop10   = posicao++
                        ))
                    }

                    runOnUiThread {
                        destino.clear()
                        destino.addAll(temp)
                        onSucesso()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun formatarData(dataIngles: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dataIngles)
            if (date != null)
                SimpleDateFormat("'Estreia' dd 'de' MMM", Locale("pt", "BR")).format(date)
            else
                "Estreia em breve"
        } catch (e: Exception) {
            "Estreia em breve"
        }
    }

    // Mesma normalização do adapter — mantém consistência na busca
    private fun normalizarNomeBanco(nome: String): String {
        var n = nome.lowercase()
        listOf("fhd", "hd", "sd", "4k", "8k", "h265", "leg", "dublado", "dub",
               "nacional", "legendado", "|", "-", "_", ".", "(", ")")
            .forEach { n = n.replace(it, " ") }
        return n.trim().replace(Regex("\\s+"), " ")
    }
}
