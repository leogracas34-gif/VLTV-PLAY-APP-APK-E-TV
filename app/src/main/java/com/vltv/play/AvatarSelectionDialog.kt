package com.vltv.play.ui

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vltv.play.R

/**
 * AvatarSelectionDialog — v3
 * - Remove letra inicial sobre o poster (era fallback, agora temos PNGs)
 * - Mantém anel colorido por categoria e anel dourado de seleção
 */
class AvatarSelectionDialog(
    context: Context,
    private val onAvatarSelected: (String) -> Unit
) : Dialog(context) {

    // ─── Modelo ────────────────────────────────────────────────────────────────

    data class AvatarItem(
        val id: String,
        val nome: String,
        val inicial: String,      // mantido no modelo mas não desenhado mais
        val drawableRes: Int,
        val categoria: String
    )

    // ─── Catálogo completo ─────────────────────────────────────────────────────

    private val todosAvatares = listOf(

        // ── Marvel ──────────────────────────────────────────────────────────
        AvatarItem("av_iron_man",     "Iron Man",       "I", R.drawable.av_iron_man,     "Marvel"),
        AvatarItem("av_spider_man",   "Spider-Man",     "S", R.drawable.av_spider_man,   "Marvel"),
        AvatarItem("av_thor",         "Thor",           "T", R.drawable.av_thor,         "Marvel"),
        AvatarItem("av_hulk",         "Hulk",           "H", R.drawable.av_hulk,         "Marvel"),
        AvatarItem("av_black_widow",  "Black Widow",    "B", R.drawable.av_black_widow,  "Marvel"),
        AvatarItem("av_cap_america",  "Cap. América",   "C", R.drawable.av_cap_america,  "Marvel"),
        AvatarItem("av_dr_strange",   "Dr. Strange",    "D", R.drawable.av_dr_strange,   "Marvel"),
        AvatarItem("av_wanda",        "Wanda",          "W", R.drawable.av_wanda,        "Marvel"),
        AvatarItem("av_black_panther","Black Panther",  "B", R.drawable.av_black_panther,"Marvel"),
        AvatarItem("av_guardioes",    "Guardiões",      "G", R.drawable.av_guardioes,    "Marvel"),
        AvatarItem("av_thanos",       "Thanos",         "T", R.drawable.av_thanos,       "Marvel"),
        AvatarItem("av_ant_man",      "Ant-Man",        "A", R.drawable.av_ant_man,      "Marvel"),

        // ── DC ──────────────────────────────────────────────────────────────
        AvatarItem("av_batman",       "Batman",         "B", R.drawable.av_batman,       "DC"),
        AvatarItem("av_superman",     "Superman",       "S", R.drawable.av_superman,     "DC"),
        AvatarItem("av_wonder_woman", "Wonder Woman",   "W", R.drawable.av_wonder_woman, "DC"),
        AvatarItem("av_the_flash",    "The Flash",      "F", R.drawable.av_the_flash,    "DC"),
        AvatarItem("av_aquaman",      "Aquaman",        "A", R.drawable.av_aquaman,      "DC"),
        AvatarItem("av_joker",        "Joker",          "J", R.drawable.av_joker,        "DC"),
        AvatarItem("av_shazam",       "Shazam",         "S", R.drawable.av_shazam,       "DC"),
        AvatarItem("av_liga_justica", "Liga Justiça",   "L", R.drawable.av_liga_justica, "DC"),

        // ── Disney / Pixar ───────────────────────────────────────────────────
        AvatarItem("av_simba",        "Simba",          "S", R.drawable.av_simba,        "Disney"),
        AvatarItem("av_moana",        "Moana",          "M", R.drawable.av_moana,        "Disney"),
        AvatarItem("av_elsa",         "Elsa",           "E", R.drawable.av_elsa,         "Disney"),
        AvatarItem("av_woody",        "Woody",          "W", R.drawable.av_woody,        "Disney"),
        AvatarItem("av_walle",        "WALL-E",         "W", R.drawable.av_walle,        "Disney"),
        AvatarItem("av_nemo",         "Nemo",           "N", R.drawable.av_nemo,         "Disney"),
        AvatarItem("av_rapunzel",     "Rapunzel",       "R", R.drawable.av_rapunzel,     "Disney"),
        AvatarItem("av_stitch",       "Stitch",         "S", R.drawable.av_stitch,       "Disney"),
        AvatarItem("av_coco",         "Coco",           "C", R.drawable.av_coco,         "Disney"),
        AvatarItem("av_ratatouille",  "Ratatouille",    "R", R.drawable.av_ratatouille,  "Disney"),

        // ── Star Wars ────────────────────────────────────────────────────────
        AvatarItem("av_darth_vader",  "Darth Vader",    "D", R.drawable.av_darth_vader,  "Star Wars"),
        AvatarItem("av_luke",         "Luke Skywalker", "L", R.drawable.av_luke,         "Star Wars"),
        AvatarItem("av_rey",          "Rey",            "R", R.drawable.av_rey,          "Star Wars"),
        AvatarItem("av_mandalorian",  "Mandalorian",    "M", R.drawable.av_mandalorian,  "Star Wars"),
        AvatarItem("av_grogu",        "Grogu",          "G", R.drawable.av_grogu,        "Star Wars"),
        AvatarItem("av_obi_wan",      "Obi-Wan",        "O", R.drawable.av_obi_wan,      "Star Wars"),
        AvatarItem("av_han_solo",     "Han Solo",       "H", R.drawable.av_han_solo,     "Star Wars"),
        AvatarItem("av_leia",         "Princesa Leia",  "L", R.drawable.av_leia,         "Star Wars"),

        // ── Séries ───────────────────────────────────────────────────────────
        AvatarItem("av_got",          "Game of Thrones","G", R.drawable.av_got,          "Séries"),
        AvatarItem("av_breaking_bad", "Breaking Bad",   "B", R.drawable.av_breaking_bad, "Séries"),
        AvatarItem("av_stranger",     "Stranger Things","S", R.drawable.av_stranger,     "Séries"),
        AvatarItem("av_wednesday",    "Wednesday",      "W", R.drawable.av_wednesday,    "Séries"),
        AvatarItem("av_loki",         "Loki",           "L", R.drawable.av_loki,         "Séries"),
        AvatarItem("av_the_boys",     "The Boys",       "T", R.drawable.av_the_boys,     "Séries"),
        AvatarItem("av_house_dragon", "House Dragon",   "H", R.drawable.av_house_dragon, "Séries"),
        AvatarItem("av_squid_game",   "Squid Game",     "S", R.drawable.av_squid_game,   "Séries"),

        // ── Ação ─────────────────────────────────────────────────────────────
        AvatarItem("av_james_bond",   "James Bond",     "J", R.drawable.av_james_bond,   "Ação"),
        AvatarItem("av_john_wick",    "John Wick",      "J", R.drawable.av_john_wick,    "Ação"),
        AvatarItem("av_indiana",      "Indiana Jones",  "I", R.drawable.av_indiana,      "Ação"),
        AvatarItem("av_jack_sparrow", "Jack Sparrow",   "J", R.drawable.av_jack_sparrow, "Ação"),
        AvatarItem("av_ethan_hunt",   "Ethan Hunt",     "E", R.drawable.av_ethan_hunt,   "Ação"),
        AvatarItem("av_velocidade",   "Velocidade",     "V", R.drawable.av_velocidade,   "Ação"),
        AvatarItem("av_matrix",       "Matrix",         "M", R.drawable.av_matrix,       "Ação"),
        AvatarItem("av_top_gun",      "Top Gun",        "T", R.drawable.av_top_gun,      "Ação"),

        // ── Anime ─────────────────────────────────────────────────────────────
        AvatarItem("av_naruto",       "Naruto",         "N", R.drawable.av_naruto,       "Anime"),
        AvatarItem("av_dragon_ball",  "Dragon Ball",    "D", R.drawable.av_dragon_ball,  "Anime"),
        AvatarItem("av_one_piece",    "One Piece",      "O", R.drawable.av_one_piece,    "Anime"),
        AvatarItem("av_one_punch",    "One Punch Man",  "S", R.drawable.av_one_punch,    "Anime"),
        AvatarItem("av_aot",          "Attack on Titan","A", R.drawable.av_aot,          "Anime"),
        AvatarItem("av_demon_slayer", "Demon Slayer",   "T", R.drawable.av_demon_slayer, "Anime"),
        AvatarItem("av_death_note",   "Death Note",     "L", R.drawable.av_death_note,   "Anime"),
        AvatarItem("av_my_hero",      "My Hero Acad.",  "D", R.drawable.av_my_hero,      "Anime"),
    )

    // ─── Cores de anel por categoria ──────────────────────────────────────────

    private val ringColorByCat = mapOf(
        "Marvel"    to Color.parseColor("#FF6B6B"),
        "DC"        to Color.parseColor("#4A90D9"),
        "Disney"    to Color.parseColor("#E040FB"),
        "Star Wars" to Color.parseColor("#00B4D8"),
        "Séries"    to Color.parseColor("#27AE60"),
        "Ação"      to Color.parseColor("#E67E22"),
        "Anime"     to Color.parseColor("#FF6EC7"),
    )

    // ─── Estado ───────────────────────────────────────────────────────────────

    private val categorias = listOf("Todos", "Marvel", "DC", "Disney", "Star Wars", "Séries", "Ação", "Anime")
    private var categoriaAtual = "Todos"
    private var idSelecionado: String? = null

    private var btnConfirmar: TextView? = null
    private var gridAdapter: AvatarGridAdapter? = null
    private val chipViews = mutableMapOf<String, TextView>()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
    private val Float.dp: Float get() = (this * context.resources.displayMetrics.density)

    private fun filtrados() =
        if (categoriaAtual == "Todos") todosAvatares
        else todosAvatares.filter { it.categoria == categoriaAtual }

    private fun atualizarBotao() {
        val ativo = idSelecionado != null
        btnConfirmar?.apply {
            isEnabled = ativo
            val bg = background as? android.graphics.drawable.GradientDrawable
            if (ativo) {
                bg?.setColor(Color.WHITE)
                bg?.setStroke(0, Color.TRANSPARENT)
                setTextColor(Color.BLACK)
            } else {
                bg?.setColor(Color.parseColor("#1A1A1A"))
                bg?.setStroke(1.dp, Color.parseColor("#2A2A2A"))
                setTextColor(Color.parseColor("#444444"))
            }
        }
    }

    private fun atualizarChips(selecionada: String) {
        chipViews.forEach { (cat, chip) ->
            val bg = chip.background as? android.graphics.drawable.GradientDrawable
            if (cat == selecionada) {
                bg?.setStroke(2.dp, Color.parseColor("#FFD700"))
                chip.setTextColor(Color.WHITE)
                chip.typeface = Typeface.DEFAULT_BOLD
            } else {
                bg?.setStroke(1.dp, Color.parseColor("#333333"))
                chip.setTextColor(Color.parseColor("#777777"))
                chip.typeface = Typeface.DEFAULT
            }
        }
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        fun divider() = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 18.dp, 20.dp, 14.dp)
        }
        header.addView(TextView(context).apply {
            text = "Escolher Avatar"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16.dp, 8.dp, 4.dp, 8.dp)
            setOnClickListener { dismiss() }
        })

        // Chips de categoria
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }
        categorias.forEach { cat ->
            val chip = TextView(context).apply {
                text = cat
                textSize = 12f
                setPadding(16.dp, 7.dp, 16.dp, 7.dp)
                setTextColor(Color.parseColor("#777777"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 20f.dp
                    setStroke(1.dp, Color.parseColor("#333333"))
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    setMargins(4.dp, 0, 4.dp, 0)
                }
                setOnClickListener {
                    categoriaAtual = cat
                    atualizarChips(cat)
                    idSelecionado = null
                    atualizarBotao()
                    gridAdapter?.updateList(filtrados())
                }
            }
            chipViews[cat] = chip
            row.addView(chip)
        }
        atualizarChips("Todos")
        scroll.addView(row)

        // Grid de avatares
        gridAdapter = AvatarGridAdapter(filtrados()) { id ->
            idSelecionado = id
            atualizarBotao()
        }
        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            layoutManager = GridLayoutManager(context, 3)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            clipToPadding = false
            adapter = gridAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(30)
        }

        // Footer / Botão confirmar
        val btn = TextView(context).apply {
            text = "Confirmar Avatar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isEnabled = false
            setTextColor(Color.parseColor("#444444"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 8f.dp
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, 52.dp)
            setOnClickListener {
                val id = idSelecionado ?: return@setOnClickListener
                onAvatarSelected(id)
                dismiss()
            }
        }
        btnConfirmar = btn

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        footer.addView(divider())
        footer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 8.dp)
        })
        footer.addView(btn)

        root.addView(header)
        root.addView(divider())
        root.addView(scroll)
        root.addView(divider())
        root.addView(recycler)
        root.addView(footer)

        setContentView(root)

        window?.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0D0D0D"))
                cornerRadius = 16f.dp
            })
            val p = attributes
            p.width  = (context.resources.displayMetrics.widthPixels  * 0.93).toInt()
            p.height = (context.resources.displayMetrics.heightPixels * 0.85).toInt()
            attributes = p
        }
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    inner class AvatarGridAdapter(
        private var list: List<AvatarItem>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AvatarGridAdapter.VH>() {

        private var selectedPos = -1

        fun updateList(nova: List<AvatarItem>) {
            selectedPos = -1
            list = nova
            notifyDataSetChanged()
        }

        private val AVATAR_SIZE get() = 88.dp
        private val RING_STROKE get() = 3.dp

        inner class VH(val container: LinearLayout) : RecyclerView.ViewHolder(container) {
            val avatarView: AvatarDrawView = container.getChildAt(0) as AvatarDrawView
            val nameText: TextView         = container.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.MarginLayoutParams(MATCH, WRAP).apply {
                    setMargins(4.dp, 10.dp, 4.dp, 10.dp)
                }
                isClickable = true
                isFocusable = true
            }

            val avatarView = AvatarDrawView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(AVATAR_SIZE, AVATAR_SIZE)
            }

            val nameText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = 6.dp
                }
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            container.addView(avatarView)
            container.addView(nameText)
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val sel  = selectedPos == position
            val ringColor = ringColorByCat[item.categoria] ?: Color.parseColor("#FFD700")

            holder.avatarView.bind(
                drawableRes  = item.drawableRes,
                ringColor    = ringColor,
                isSelected   = sel,
                ringStrokePx = RING_STROKE
            )

            holder.nameText.text = item.nome
            holder.nameText.setTextColor(if (sel) Color.WHITE else Color.parseColor("#888888"))
            holder.nameText.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            holder.container.setOnClickListener {
                val prev = selectedPos
                selectedPos = holder.adapterPosition
                if (prev >= 0) notifyItemChanged(prev)
                notifyItemChanged(selectedPos)
                onClick(item.id)
            }

            holder.container.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                            .scaleY(if (hasFocus) 1.08f else 1f)
                            .setDuration(120).start()
            }
        }

        override fun getItemCount() = list.size
    }

    // ─── View customizada para desenhar o avatar via Canvas ───────────────────

    /**
     * Desenha:
     *  1. O drawable (poster PNG) com clip circular
     *  2. Anel colorido da categoria (fino, sempre visível)
     *  3. Anel dourado de seleção (quando selecionado)
     *
     * ✅ A letra inicial foi REMOVIDA — os PNGs já mostram o personagem.
     */
    inner class AvatarDrawView(ctx: Context) : View(ctx) {

        private var drawableRes  = -1
        private var ringColor    = Color.YELLOW
        private var isSelected   = false
        private var ringStrokePx = 3.dp

        private var bgDrawable: Drawable? = null

        // Paint do anel
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        fun bind(
            drawableRes: Int,
            ringColor: Int,
            isSelected: Boolean,
            ringStrokePx: Int
        ) {
            this.drawableRes  = drawableRes
            this.ringColor    = ringColor
            this.isSelected   = isSelected
            this.ringStrokePx = ringStrokePx
            bgDrawable = ContextCompat.getDrawable(context, drawableRes)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w      = width.toFloat()
            val h      = height.toFloat()
            val cx     = w / 2f
            val cy     = h / 2f
            val radius = (w.coerceAtMost(h) / 2f) - ringStrokePx

            // 1. Clip circular + desenha o poster
            val clipPath = Path().apply {
                addCircle(cx, cy, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            bgDrawable?.setBounds(0, 0, width, height)
            bgDrawable?.draw(canvas)
            canvas.restore()

            // 2. Anel de seleção dourado OU anel sutil da categoria
            if (isSelected) {
                ringPaint.color       = Color.parseColor("#FFD700")
                ringPaint.strokeWidth = ringStrokePx.toFloat()
                canvas.drawCircle(cx, cy, radius - ringStrokePx / 2f, ringPaint)
            } else {
                ringPaint.color       = ringColor
                ringPaint.strokeWidth = 1.5f.dp
                canvas.drawCircle(cx, cy, radius - 1.dp, ringPaint)
            }
        }
    }

    // ─── Constantes de layout ──────────────────────────────────────────────────

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
