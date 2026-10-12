package com.gerrit.gameradar

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GAME = "game"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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

        ImageLoader.load(RawgApi.thumb(game.image, 1280, 720), findViewById<ImageView>(R.id.hero))
        findViewById<TextView>(R.id.title).text = game.name
        findViewById<TextView>(R.id.date).text = Fmt.dateLine(game)
        findViewById<LinearLayout>(R.id.badges).showPlatformBadges(game.platformIds)
        val genres = findViewById<TextView>(R.id.genres)
        genres.text = game.genres.joinToString(" · ")
        genres.visibility = if (game.genres.isEmpty()) View.GONE else View.VISIBLE

        val linksBox = findViewById<LinearLayout>(R.id.links)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val description = findViewById<TextView>(R.id.description)

        // Show fallback links straight away, then upgrade them once store data arrives
        showLinks(linksBox, game, emptyList(), null)

        val key = Prefs.key(this)
        executor.execute {
            val stores = try { RawgApi.stores(key, game.id) } catch (e: Exception) { emptyList() }
            val detail = try { RawgApi.details(key, game.id) } catch (e: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                progress.visibility = View.GONE
                showLinks(linksBox, game, stores, detail?.website)
                description.text = detail?.description?.takeIf { it.isNotBlank() }
                    ?: "No description available yet."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun showLinks(box: LinearLayout, game: Game, stores: List<StoreLink>, website: String?) {
        val links = LinkedHashMap<String, String>()
        val q = Uri.encode(game.name)

        // Real store pages from RAWG (only PC / PlayStation / Xbox storefronts)
        val haveStores = HashSet<Int>()
        for (sid in RawgApi.STORES.keys) {
            val fromApi = stores.firstOrNull { it.storeId == sid }
            val listed = fromApi != null || sid in game.storeIds
            if (!listed) continue
            val url = fromApi?.url?.takeIf { it.startsWith("http") } ?: storeSearch(sid, q)
            links[RawgApi.STORES.getValue(sid)] = url
            haveStores.add(sid)
        }

        // Make sure every platform the game is on has at least one link
        val ids = game.platformIds
        if (4 in ids && haveStores.none { it == 1 || it == 11 || it == 5 || it == 9 }) {
            links["Search Steam"] = storeSearch(1, q)
        }
        if (187 in ids && 3 !in haveStores) {
            links["Search PlayStation Store"] = storeSearch(3, q)
        }
        if ((186 in ids || 1 in ids) && 2 !in haveStores) {
            links["Search Xbox Store"] = storeSearch(2, q)
        }

        if (website != null) links["Official website"] = website
        links["Trailers on YouTube"] = "https://www.youtube.com/results?search_query=" +
            Uri.encode(game.name + " trailer")
        links["View on RAWG"] = "https://rawg.io/games/${game.slug}"

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

    private fun storeSearch(storeId: Int, q: String): String = when (storeId) {
        1 -> "https://store.steampowered.com/search/?term=$q"
        3 -> "https://store.playstation.com/en-us/search/$q"
        2, 7 -> "https://www.xbox.com/en-US/search/results/games?q=$q"
        11 -> "https://store.epicgames.com/en-US/browse?q=$q"
        5 -> "https://www.gog.com/en/games?query=$q"
        9 -> "https://itch.io/search?q=$q"
        else -> "https://www.google.com/search?q=$q"
    }
}
