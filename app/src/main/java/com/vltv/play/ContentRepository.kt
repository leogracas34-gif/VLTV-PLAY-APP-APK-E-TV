package com.vltv.play

import android.content.Context
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.LiveStreamEntity
import kotlinx.coroutines.*

/**
 * Repositório singleton em memória.
 *
 * Carregado UMA vez pelo VLTVApplication antes de qualquer Activity abrir.
 * Todas as telas lêem daqui — zero query no banco na hora de exibir.
 *
 * Fluxo:
 *   App inicia → VLTVApplication.onCreate() → ContentRepository.preCarregar()
 *   VodActivity.onCreate() → ContentRepository.getVodsByCategory("123") → instantâneo
 *   SeriesActivity.onCreate() → ContentRepository.getSeriesByCategory("456") → instantâneo
 */
object ContentRepository {

    // ── Listas planas (usadas pela Home, busca, Top10, Novidades) ─────────────
    var vods:   List<VodEntity>        = emptyList(); private set
    var series: List<SeriesEntity>     = emptyList(); private set
    var lives:  List<LiveStreamEntity> = emptyList(); private set

    // ── Mapas por categoria (usados por VodActivity e SeriesActivity) ─────────
    // Chave = category_id. Acesso em O(1), completamente instantâneo.
    private var vodsPorCategoria:   Map<String, List<VodEntity>>    = emptyMap()
    private var seriesPorCategoria: Map<String, List<SeriesEntity>> = emptyMap()

    // ── Estado de carregamento ────────────────────────────────────────────────
    var pronto: Boolean = false; private set

    // ── Callbacks para notificar quem estiver esperando ───────────────────────
    private val listeners = mutableListOf<() -> Unit>()

    fun aoFicarPronto(cb: () -> Unit) {
        if (pronto) { cb(); return }
        listeners.add(cb)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Acesso por categoria — O(1), sem query, sem coroutine, sem delay
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retorna os VODs de uma categoria diretamente da memória.
     * Se o banco ainda não foi carregado, retorna lista vazia (sem crash).
     */
    fun getVodsByCategory(categoryId: String): List<VodEntity> =
        vodsPorCategoria[categoryId] ?: emptyList()

    /**
     * Retorna as séries de uma categoria diretamente da memória.
     */
    fun getSeriesByCategory(categoryId: String): List<SeriesEntity> =
        seriesPorCategoria[categoryId] ?: emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    // Pré-carregamento — chamado pelo VLTVApplication
    // ─────────────────────────────────────────────────────────────────────────
    fun preCarregar(context: Context) {
        if (pronto) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).streamDao()

                // Carrega TUDO em paralelo — uma única leitura do banco
                val jVods   = async { dao.getAllVods() }
                val jSeries = async { dao.getAllSeries() }
                val jLives  = async { try { dao.searchLive("") } catch (e: Exception) { emptyList() } }

                val todosVods   = jVods.await()
                val todasSeries = jSeries.await()
                val todasLives  = jLives.await()

                // Listas planas para Home / busca / Top10 / Novidades
                vods   = todosVods
                series = todasSeries
                lives  = todasLives

                // Mapas por categoria — acesso O(1) nas telas de VOD e Séries
                // groupBy é rápido mesmo com 10.000+ itens (< 5ms)
                vodsPorCategoria   = todosVods.groupBy { it.category_id }
                seriesPorCategoria = todasSeries.groupBy { it.category_id }

                pronto = true

                withContext(Dispatchers.Main) {
                    listeners.forEach { it() }
                    listeners.clear()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                pronto = true
                withContext(Dispatchers.Main) {
                    listeners.forEach { it() }
                    listeners.clear()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Atualiza dados em memória após sincronização da API em background
    // Reconstrói os mapas por categoria automaticamente
    // ─────────────────────────────────────────────────────────────────────────

    fun atualizarVods(novos: List<VodEntity>) {
        vods = novos
        vodsPorCategoria = novos.groupBy { it.category_id }
    }

    fun atualizarSeries(novas: List<SeriesEntity>) {
        series = novas
        seriesPorCategoria = novas.groupBy { it.category_id }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inserção incremental por categoria (chamada pelo VodActivity / SeriesActivity
    // após a API retornar os filmes de uma categoria específica)
    // Atualiza apenas aquela categoria no mapa, sem recarregar tudo
    // ─────────────────────────────────────────────────────────────────────────

    fun atualizarCategoriaVod(categoryId: String, itens: List<VodEntity>) {
        val mapaAtualizado = vodsPorCategoria.toMutableMap()
        mapaAtualizado[categoryId] = itens
        vodsPorCategoria = mapaAtualizado

        // Recalcula a lista plana incluindo os novos itens
        val todosAtualizados = vodsPorCategoria.values.flatten().distinctBy { it.stream_id }
        vods = todosAtualizados
    }

    fun atualizarCategoriaSeries(categoryId: String, itens: List<SeriesEntity>) {
        val mapaAtualizado = seriesPorCategoria.toMutableMap()
        mapaAtualizado[categoryId] = itens
        seriesPorCategoria = mapaAtualizado

        val todosAtualizados = seriesPorCategoria.values.flatten().distinctBy { it.series_id }
        series = todosAtualizados
    }

    // ── Limpa tudo (usado na troca de credenciais) ────────────────────────────
    fun limpar() {
        vods   = emptyList()
        series = emptyList()
        lives  = emptyList()
        vodsPorCategoria   = emptyMap()
        seriesPorCategoria = emptyMap()
        pronto = false
        listeners.clear()
    }
}
