package com.gerrit.gameradar

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GAME = "game"

        /** Sites that aren't stores or the official page. */
        private val SKIP_HOSTS = listOf(
            "wikipedia.", "wikia.", "fandom.", "facebook.", "twitter.", "x.com", "instagram.",
            "twitch.", "reddit.", "discord.", "tiktok.", "bsky.", "apple.com", "play.google.",
            "nintendo.", "youtube.", "youtu.be", "threads.net", "linkedin."
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        @Suppress("DEPRECATION")
        val game = intent.getSerializableExtra(EXTRA_GAME) as? Game
        if (game == null) {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = game.name
        toolbar.setNavigationOnClickListener { finish() }

        val hero = IgdbApi.screenshotUrl(game.screenshotId) ?: IgdbApi.coverUrl(game.coverId)
        ImageLoader.load(hero, findViewById<ImageView>(R.id.hero))
        findViewById<TextView>(R.id.title).text = game.name
        findViewById<TextView>(R.id.date).text = Fmt.dateLine(game)
        findViewById<LinearLayout>(R.id.badges).showPlatformBadges(game.platformIds)
        val genres = findViewById<TextView>(R.id.genres)
        genres.text = game.genres.joinToString(" · ")
        genres.visibility = if (game.genres.isEmpty()) View.GONE else View.VISIBLE

        findViewById<ProgressBar>(R.id.progress).visibility = View.GONE
        findViewById<TextView>(R.id.description).text =
            game.summary.ifBlank { "No description available yet." }

        showLinks(findViewById(R.id.links), game)
    }

    private fun showLinks(box: LinearLayout, game: Game) {
        val links = LinkedHashMap<String, String>()
        val q = Uri.encode(game.name)
        var official: String? = null

        for (url in game.websites) {
            val u = url.lowercase()
            val label = when {
                "steampowered.com" in u -> "Steam"
                "epicgames.com" in u -> "Epic Games Store"
                "gog.com" in u -> "GOG"
                "playstation.com" in u -> "PlayStation Store"
                "xbox.com" in u || "microsoft.com" in u -> "Xbox / Microsoft Store"
                "itch.io" in u -> "itch.io"
                SKIP_HOSTS.any { it in u } -> null
                else -> { if (official == null) official = url; null }
            }
            if (label != null && label !in links) links[label] = url
        }

        // Make sure every platform the game is on has a store link
        val ids = game.platformIds
        if (6 in ids && links.keys.none { it == "Steam" || it == "Epic Games Store" || it == "GOG" || it == "itch.io" }) {
            links["Search Steam"] = "https://store.steampowered.com/search/?term=$q"
        }
        if (167 in ids && "PlayStation Store" !in links) {
            links["Search PlayStation Store"] = "https://store.playstation.com/en-us/search/$q"
        }
        if ((169 in ids || 49 in ids) && "Xbox / Microsoft Store" !in links) {
            links["Search Xbox Store"] = "https://www.xbox.com/en-US/search/results/games?q=$q"
        }

        official?.let { links["Official website"] = it }
        links["Trailers on YouTube"] = "https://www.youtube.com/results?search_query=" +
            Uri.encode(game.name + " trailer")
        links["View on IGDB"] = game.igdbUrl ?: "https://www.igdb.com/games/${game.slug}"

        box.removeAllViews()
        for ((label, url) in links) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = label
            btn.isAllCaps = false
            btn.setOnClickListener { openUrl(url) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            box.addView(btn, lp)
        }
    }
}
