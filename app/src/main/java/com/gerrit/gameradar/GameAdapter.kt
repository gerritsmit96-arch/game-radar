package com.gerrit.gameradar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GameAdapter(private val onClick: (Game) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val game: Game) : Row()
        object Footer : Row()
    }

    private val games = ArrayList<Game>()
    private val seen = HashSet<String>()
    private var rows: List<Row> = emptyList()
    private var grouped = true
    private var footer = false

    val gameCount: Int get() = games.size

    fun setGames(list: List<Game>, groupByMonth: Boolean) {
        games.clear()
        seen.clear()
        grouped = groupByMonth
        footer = false
        addInternal(list)
        rebuild()
    }

    fun addGames(list: List<Game>) {
        addInternal(list)
        rebuild()
    }

    fun setFooterLoading(on: Boolean) {
        if (footer != on) {
            footer = on
            rebuild()
        }
    }

    private fun addInternal(list: List<Game>) {
        for (g in list) if (seen.add(g.key)) games.add(g)
    }

    @Suppress("NotifyDataSetChanged")
    private fun rebuild() {
        val out = ArrayList<Row>()
        var lastHeader: String? = null
        for (g in games) {
            if (grouped) {
                val h = Fmt.monthHeader(g)
                if (h != lastHeader) {
                    out.add(Row.Header(h))
                    lastHeader = h
                }
            }
            out.add(Row.Item(g))
        }
        if (footer) out.add(Row.Footer)
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.Item -> 1
        Row.Footer -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderHolder(inf.inflate(R.layout.item_header, parent, false))
            1 -> GameHolder(inf.inflate(R.layout.item_game, parent, false))
            else -> FooterHolder(inf.inflate(R.layout.item_footer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).title.text = row.title
            is Row.Item -> (holder as GameHolder).bind(row.game)
            Row.Footer -> Unit
        }
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.headerTitle)
    }

    private class FooterHolder(v: View) : RecyclerView.ViewHolder(v)

    private inner class GameHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val title: TextView = v.findViewById(R.id.title)
        private val date: TextView = v.findViewById(R.id.date)
        private val badges: LinearLayout = v.findViewById(R.id.badges)
        private val genres: TextView = v.findViewById(R.id.genres)

        fun bind(g: Game) {
            title.text = g.name
            date.text = Fmt.dateLine(g)
            badges.showPlatformBadges(g.platformIds)
            genres.text = g.genres.joinToString(" · ")
            genres.visibility = if (g.genres.isEmpty()) View.GONE else View.VISIBLE
            ImageLoader.load(IgdbApi.coverUrl(g.coverId), cover)
            itemView.setOnClickListener { onClick(g) }
        }
    }
}
