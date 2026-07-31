package com.vltv.play.retro

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.vltv.play.R
import com.vltv.play.isTelevisionDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tela "Jogos Retrô" da Home.
 * Busca o catálogo em https://cdn.vltvplay.tech/retro/games.json e exibe em grade.
 *
 * ✅ VERSÃO DE DIAGNÓSTICO: mostra um Toast com o erro exato caso a busca
 * do catálogo falhe (timeout, erro de rede, JSON inválido, etc.), em vez
 * de simplesmente ficar com a tela preta sem explicação. Depois que
 * resolvermos o problema, dá pra tirar esses Toasts.
 */
class RetroGamesActivity : AppCompatActivity() {

    companion object {
        // Atenção: o /retro/ está dentro do server block de cdn.vltvplay.tech na VPS
        private const val CATALOG_URL = "https://cdn.vltvplay.tech/retro/games.json"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_retro_games)

        recyclerView = findViewById(R.id.recyclerRetroGames)
        progressBar = findViewById(R.id.progressRetroGames)
        emptyView = findViewById(R.id.textRetroEmpty)

        // ✅ Mesma lógica usada em SearchActivity: mais colunas na TV
        // (tela maior, landscape) do que no celular (retrato).
        val spanCount = if (isTelevisionDevice()) 5 else 3
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)

        Toast.makeText(this, "Buscando catálogo...", Toast.LENGTH_SHORT).show()
        loadCatalog()
    }

    private fun loadCatalog() {
        progressBar.visibility = ProgressBar.VISIBLE
        emptyView.visibility = TextView.GONE

        CoroutineScope(Dispatchers.Main).launch {
            val resultado = withContext(Dispatchers.IO) { fetchGamesComDiagnostico() }

            progressBar.visibility = ProgressBar.GONE

            when (resultado) {
                is ResultadoCatalogo.Sucesso -> {
                    if (resultado.games.isEmpty()) {
                        emptyView.visibility = TextView.VISIBLE
                        emptyView.text = getString(R.string.retro_games_empty)
                        Toast.makeText(this@RetroGamesActivity, "Catálogo veio vazio (0 jogos)", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    Toast.makeText(this@RetroGamesActivity, "${resultado.games.size} jogo(s) encontrado(s)", Toast.LENGTH_SHORT).show()
                    recyclerView.adapter = RetroGameAdapter(resultado.games) { game ->
                        openGame(game)
                    }
                }
                is ResultadoCatalogo.Erro -> {
                    emptyView.visibility = TextView.VISIBLE
                    emptyView.text = "Erro ao carregar: ${resultado.mensagem}"
                    Toast.makeText(this@RetroGamesActivity, "Erro: ${resultado.mensagem}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private sealed class ResultadoCatalogo {
        data class Sucesso(val games: List<RetroGame>) : ResultadoCatalogo()
        data class Erro(val mensagem: String) : ResultadoCatalogo()
    }

    private fun fetchGamesComDiagnostico(): ResultadoCatalogo {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                return ResultadoCatalogo.Erro("HTTP $code ao buscar o catálogo")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, RetroGame::class.java
            ).type
            val games: List<RetroGame> = Gson().fromJson(response, type) ?: emptyList()
            ResultadoCatalogo.Sucesso(games)
        } catch (e: java.net.UnknownHostException) {
            ResultadoCatalogo.Erro("DNS/host não encontrado (${e.message})")
        } catch (e: javax.net.ssl.SSLException) {
            ResultadoCatalogo.Erro("Falha de SSL/HTTPS (${e.message})")
        } catch (e: java.net.SocketTimeoutException) {
            ResultadoCatalogo.Erro("Timeout (VPS demorou pra responder)")
        } catch (e: com.google.gson.JsonSyntaxException) {
            ResultadoCatalogo.Erro("JSON inválido no games.json (${e.message})")
        } catch (e: Exception) {
            ResultadoCatalogo.Erro("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openGame(game: RetroGame) {
        val intent = Intent(this, RetroGamePlayerActivity::class.java).apply {
            putExtra(RetroGamePlayerActivity.EXTRA_ROM_URL, game.romUrl)
            putExtra(RetroGamePlayerActivity.EXTRA_CORE, game.core)
            putExtra(RetroGamePlayerActivity.EXTRA_TITLE, game.name)
        }
        startActivity(intent)
    }
}

private fun BufferedReader.readText(): String {
    val sb = StringBuilder()
    var line: String?
    while (this.readLine().also { line = it } != null) {
        sb.append(line)
    }
    return sb.toString()
}
