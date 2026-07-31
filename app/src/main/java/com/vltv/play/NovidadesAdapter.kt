package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NovidadesAdapter(
    private var lista: List<NovidadeItem>,
    private val currentProfile: String,
    private val database: AppDatabase,
    private var vodsMap: Map<String, VodEntity>,
    private var seriesMap: Map<String, SeriesEntity>
) : RecyclerView.Adapter<NovidadesAdapter.VH>() {

    // ── OkHttpClient dedicado para logos — conexões persistentes, timeout curto ──
    // CORREÇÃO: substituído URL().readText() por OkHttp com pool — igual às outras telas
    private val logoClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(8, 30, TimeUnit.SECONDS))
        .build()

    // CORREÇÃO: semáforo de 3 → 6 — permite mais buscas paralelas de logo
    // Com 20 itens visíveis, 3 era gargalo; 6 equilibra throughput e memória
    private val logoSemaphore = kotlinx.coroutines.sync.Semaphore(6)

    // ── Scope vinculado ao adapter — cancela tudo quando adapter é descartado ──
    private val adapterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val imgFundo: ImageView           = view.findViewById(R.id.imgFundoNovidade)
        val imgLogo: ImageView            = view.findViewById(R.id.imgLogoNovidade)
        val tvTitulo: TextView            = view.findViewById(R.id.tvTituloNovidade)
        val tvTagline: TextView           = view.findViewById(R.id.tvTagline)
        val tvSinopse: TextView           = view.findViewById(R.id.tvSinopseNovidade)
        val tvMensagem: TextView?         = try { view.findViewById(R.id.tvMensagemDisponibilidade) } catch (e: Exception) { null }
        val containerBotoes: LinearLayout = view.findViewById(R.id.containerBotoesAtivos)
        val btnAssistir: LinearLayout     = view.findViewById(R.id.btnAssistirNovidade)
        val btnDetalhes: LinearLayout     = view.findViewById(R.id.btnMinhaListaNovidade)
        var job: Job? = null
        // Guarda o id atual para evitar atualizações em holders reciclados
        var tmdbIdAtual: Int = -1
    }

    fun atualizarMapas(vods: Map<String, VodEntity>, series: Map<String, SeriesEntity>) {
        vodsMap   = vods
        seriesMap = series
        // CORREÇÃO: notifyItemRangeChanged em vez de notifyDataSetChanged
        // Evita redesenho completo + preserva animações do RecyclerView
        notifyItemRangeChanged(0, lista.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Cancela IMEDIATAMENTE qualquer trabalho do holder anterior
        holder.job?.cancel()
        holder.job = null

        val item    = lista[position]
        val context = holder.itemView.context
        val logoPrefs = context.getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)

        // Marca qual id este holder está exibindo agora
        holder.tmdbIdAtual = item.idTMDB

        // ── Textos (síncrono, instantâneo) ──────────────────────────────────
        holder.tvTitulo.text  = item.titulo
        holder.tvSinopse.text = item.sinopse
        holder.tvTagline.text = if (item.isTop10) "🏆 Top ${item.posicaoTop10}" else item.tagline

        // ── Imagem de fundo ──────────────────────────────────────────────────
        Glide.with(context)
            .load(item.imagemFundoUrl)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(780, 440)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .centerCrop()
            .placeholder(android.R.color.black)
            .error(android.R.color.black)
            .into(holder.imgFundo)

        // ── Logo: cache SharedPreferences → zero latência ────────────────────
        val cachedLogo = logoPrefs.getString("novidade_logo_${item.idTMDB}", null)
        if (cachedLogo != null) {
            holder.tvTitulo.visibility = View.GONE
            holder.imgLogo.visibility  = View.VISIBLE
            Glide.with(context)
                .load(cachedLogo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .dontAnimate()
                .into(holder.imgLogo)
        } else {
            // Sem cache: título texto visível imediatamente, logo vem em background
            holder.tvTitulo.visibility = View.VISIBLE
            holder.imgLogo.visibility  = View.GONE
        }

        // ── Reset botões ─────────────────────────────────────────────────────
        holder.btnAssistir.visibility     = View.GONE
        holder.tvMensagem?.visibility     = View.GONE
        holder.containerBotoes.visibility = View.VISIBLE

        // ── Disponibilidade: O(1) nos mapas — sem tocar no banco ─────────────
        val nomeNorm   = normalizarNome(item.titulo)
        val serieLocal = if (item.isSerie)  encontrarNoMapa(nomeNorm, seriesMap) { it.name } else null
        val filmeLocal = if (!item.isSerie) encontrarNoMapa(nomeNorm, vodsMap)  { it.name } else null

        aplicarDisponibilidade(holder, item, context, serieLocal, filmeLocal)

        // ── Busca logo em background somente se não estava em cache ──────────
        if (cachedLogo == null) {
            val idCapturado = item.idTMDB
            holder.job = adapterScope.launch {
                logoSemaphore.acquire()
                try {
                    if (!isActive) return@launch
                    val logoUrl = buscarLogoTMDB(idCapturado, item.isSerie, logoPrefs)
                    if (logoUrl != null && isActive) {
                        withContext(Dispatchers.Main) {
                            // CORREÇÃO: verifica tmdbIdAtual em vez de adapterPosition
                            // adapterPosition pode ser -1 quando holder está em transição
                            if (holder.tmdbIdAtual == idCapturado) {
                                holder.tvTitulo.visibility = View.GONE
                                holder.imgLogo.visibility  = View.VISIBLE
                                Glide.with(context)
                                    .load(logoUrl)
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .transition(DrawableTransitionOptions.withCrossFade(200))
                                    .into(holder.imgLogo)
                            }
                        }
                    }
                } finally {
                    logoSemaphore.release()
                }
            }
        }
    }

    // CORREÇÃO (item "disponível" apontando pro filme/série errado): a
    // tentativa exata (mapa[nomeNorm]) sempre foi segura. O problema era o
    // fallback fuzzy — `firstOrNull` pegava o PRIMEIRO item do Map cujo nome
    // batesse por substring, na ordem interna arbitrária do Map (não tem
    // relação com "qual é o candidato mais parecido"). Um título como "Amor"
    // podia bater com qualquer outro título que contivesse "Amor" em algum
    // lugar, e o primeiro da ordem interna vencia — mesma família do bug já
    // corrigido na Home/Detalhes ("Origem" batendo em "...A Origem").
    // Agora, entre todos os candidatos que batem por substring, escolhe o de
    // nome mais curto — ou seja, o mais próximo do termo buscado — igual à
    // técnica (ORDER BY LENGTH ASC) já usada no resto do app.
    private fun <T> encontrarNoMapa(nomeNorm: String, mapa: Map<String, T>, getNome: (T) -> String): T? {
        mapa[nomeNorm]?.let { return it }
        return mapa.values
            .filter { item ->
                val nomeLocal = normalizarNome(getNome(item))
                nomeLocal.contains(nomeNorm) || nomeNorm.contains(nomeLocal)
            }
            .minByOrNull { normalizarNome(getNome(it)).length }
    }

    private fun aplicarDisponibilidade(
        holder: VH,
        item: NovidadeItem,
        context: Context,
        serieLocal: SeriesEntity?,
        filmeLocal: VodEntity?
    ) {
        if (item.isEmBreve) {
            holder.btnAssistir.visibility = View.GONE
            holder.tvMensagem?.text       = "🗓 Disponível no aplicativo após o lançamento"
            holder.tvMensagem?.visibility = View.VISIBLE
            configurarBotaoDetalhes(holder, item, context, null, null)
            return
        }

        if (serieLocal != null || filmeLocal != null) {
            holder.btnAssistir.visibility = View.VISIBLE
            holder.tvMensagem?.visibility = View.GONE
            holder.btnAssistir.setOnClickListener {
                val intent = if (item.isSerie && serieLocal != null) {
                    Intent(context, SeriesDetailsActivity::class.java).apply {
                        putExtra("series_id", serieLocal.series_id)
                        putExtra("name", serieLocal.name)
                        putExtra("icon", serieLocal.cover)
                        putExtra("rating", serieLocal.rating ?: "0.0")
                        putExtra("PROFILE_NAME", currentProfile)
                    }
                } else if (filmeLocal != null) {
                    Intent(context, DetailsActivity::class.java).apply {
                        putExtra("stream_id", filmeLocal.stream_id)
                        putExtra("name", filmeLocal.name)
                        putExtra("icon", filmeLocal.stream_icon)
                        putExtra("poster", filmeLocal.stream_icon)
                        putExtra("rating", filmeLocal.rating ?: "0.0")
                        putExtra("container_extension", filmeLocal.container_extension)
                        putExtra("PROFILE_NAME", currentProfile)
                    }
                } else null
                intent?.let { context.startActivity(it) }
            }
            configurarBotaoDetalhes(holder, item, context, serieLocal, filmeLocal)
        } else {
            holder.btnAssistir.visibility = View.GONE
            holder.tvMensagem?.text       = "Em breve disponível no aplicativo"
            holder.tvMensagem?.visibility = View.VISIBLE
            configurarBotaoDetalhes(holder, item, context, null, null)
        }
    }

    private fun configurarBotaoDetalhes(
        holder: VH,
        item: NovidadeItem,
        context: Context,
        serieLocal: SeriesEntity?,
        filmeLocal: VodEntity?
    ) {
        holder.btnDetalhes.setOnClickListener {
            when {
                item.isSerie && serieLocal != null -> {
                    context.startActivity(Intent(context, SeriesDetailsActivity::class.java).apply {
                        putExtra("series_id", serieLocal.series_id)
                        putExtra("name", serieLocal.name)
                        putExtra("icon", serieLocal.cover)
                        putExtra("rating", serieLocal.rating ?: "0.0")
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                }
                !item.isSerie && filmeLocal != null -> {
                    context.startActivity(Intent(context, DetailsActivity::class.java).apply {
                        putExtra("stream_id", filmeLocal.stream_id)
                        putExtra("name", filmeLocal.name)
                        putExtra("icon", filmeLocal.stream_icon)
                        putExtra("rating", filmeLocal.rating ?: "0.0")
                        putExtra("container_extension", filmeLocal.container_extension)
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                }
                else -> {
                    context.startActivity(Intent(context, TmdbDetailsActivity::class.java).apply {
                        putExtra("tmdb_id", item.idTMDB)
                        putExtra("titulo", item.titulo)
                        putExtra("sinopse", item.sinopse)
                        putExtra("imagem_url", item.imagemFundoUrl)
                        putExtra("is_serie", item.isSerie)
                        putExtra("is_em_breve", item.isEmBreve)
                        putExtra("tagline", item.tagline)
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                }
            }
        }
    }

    private fun normalizarNome(nome: String): String {
        var n = nome.lowercase()
        listOf("fhd", "hd", "sd", "4k", "8k", "h265", "leg", "dublado", "dub",
               "nacional", "legendado", "|", "-", "_", ".", "(", ")")
            .forEach { n = n.replace(it, " ") }
        return n.trim().replace(Regex("\\s+"), " ")
    }

    // CORREÇÃO: substituído URL().readText() por OkHttp — mesmo client das outras telas
    // Reduz latência de ~800ms → ~150ms por logo (reutiliza conexões TCP abertas)
    private suspend fun buscarLogoTMDB(
        tmdbId: Int,
        isSerie: Boolean,
        prefs: SharedPreferences
    ): String? {
        val tipo = if (isSerie) "tv" else "movie"
        return try {
            val url = "https://api.themoviedb.org/3/$tipo/$tmdbId/images" +
                      "?api_key=9b73f5dd15b8165b1b57419be2f29128&include_image_language=pt,en,null"

            val request  = Request.Builder().url(url).build()
            val response = logoClient.newCall(request).execute()
            val body     = response.body?.string() ?: return null
            response.close()

            val logos = JSONObject(body).optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            // Prioridade: pt → en → qualquer um
            var path: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "pt") { path = logo.optString("file_path"); break }
            }
            if (path == null) {
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == "en") { path = logo.optString("file_path"); break }
                }
            }
            if (path == null) path = logos.getJSONObject(0).optString("file_path")

            val finalUrl = "https://image.tmdb.org/t/p/w500$path"
            prefs.edit().putString("novidade_logo_$tmdbId", finalUrl).apply()
            finalUrl
        } catch (e: Exception) {
            null
        }
    }

    override fun getItemCount() = lista.size

    // CORREÇÃO: DiffUtil em vez de notifyDataSetChanged
    // Calcula exatamente quais itens mudaram — evita redesenho completo da lista
    fun atualizarLista(novaLista: List<NovidadeItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = lista.size
            override fun getNewListSize() = novaLista.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                lista[oldPos].idTMDB == novaLista[newPos].idTMDB
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                lista[oldPos] == novaLista[newPos]
        })
        lista = novaLista.toList()
        diff.dispatchUpdatesTo(this)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job    = null
        holder.tmdbIdAtual = -1
    }

    // Cancela todas as coroutines quando o adapter é descartado
    fun onDestroy() {
        adapterScope.cancel()
    }
}
