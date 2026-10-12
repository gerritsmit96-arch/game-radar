package com.gerrit.gameradar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.time.LocalDate
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var status: TextView
    private lateinit var adapter: GameAdapter

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // Filters
    private var tab = 0                       // 0 = upcoming, 1 = last 60 days
    private var platform = PlatformFilter.ALL
    private var popularFirst = false
    private var hideObscure = true

    // Paging state
    private var page = 1
    private var hasNext = true
    private var loading = false
    private var generation = 0

    private class Query(
        val from: LocalDate,
        val to: LocalDate,
        val platforms: String,
        val ordering: String,
        val popular: Boolean,
        val hideObscure: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> { reload(); true }
                R.id.action_key -> { showKeyDialog(); true }
                else -> false
            }
        }

        recycler = findViewById(R.id.recycler)
        swipe = findViewById(R.id.swipe)
        status = findViewById(R.id.status)

        adapter = GameAdapter { game ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_GAME, game))
        }
        layoutManager = LinearLayoutManager(this)
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) rv.post { maybeLoadMore() }
            }
        })
        swipe.setOnRefreshListener { reload() }

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addTab(tabs.newTab().setText("Upcoming"))
        tabs.addTab(tabs.newTab().setText("Last 60 days"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                reload()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { recycler.scrollToPosition(0) }
        })

        findViewById<ChipGroup>(R.id.platformGroup).setOnCheckedStateChangeListener { _, ids ->
            platform = when (ids.firstOrNull()) {
                R.id.chipPc -> PlatformFilter.PC
                R.id.chipPs5 -> PlatformFilter.PS5
                R.id.chipXbox -> PlatformFilter.XBOX
                else -> PlatformFilter.ALL
            }
            reload()
        }
        findViewById<Chip>(R.id.chipPopular).setOnCheckedChangeListener { _, checked ->
            popularFirst = checked
            reload()
        }
        findViewById<Chip>(R.id.chipHide).setOnCheckedChangeListener { _, checked ->
            hideObscure = checked
            reload()
        }

        findViewById<TextView>(R.id.attribution).setOnClickListener { openUrl("https://rawg.io") }

        if (Prefs.key(this).isBlank()) {
            showMissingKey()
            showKeyDialog()
        } else {
            reload()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun currentQuery(): Query {
        val today = LocalDate.now()
        return if (tab == 0) {
            Query(
                from = today.plusDays(1),
                to = today.plusYears(5),
                platforms = platform.param,
                ordering = if (popularFirst) "-added" else "released",
                popular = popularFirst,
                hideObscure = hideObscure
            )
        } else {
            Query(
                from = today.minusDays(60),
                to = today,
                platforms = platform.param,
                ordering = if (popularFirst) "-added" else "-released",
                popular = popularFirst,
                hideObscure = hideObscure
            )
        }
    }

    private fun reload() {
        generation++
        loading = false
        page = 1
        hasNext = true
        adapter.setGames(emptyList(), groupByMonth = !popularFirst)
        status.visibility = View.GONE
        loadMore()
    }

    private fun maybeLoadMore() {
        if (!hasNext || loading) return
        val last = layoutManager.findLastVisibleItemPosition()
        if (last >= adapter.itemCount - 8) loadMore()
    }

    private fun loadMore() {
        val key = Prefs.key(this)
        if (key.isBlank()) {
            swipe.isRefreshing = false
            showMissingKey()
            return
        }
        if (loading || !hasNext) return
        loading = true

        val gen = generation
        val startPage = page
        val q = currentQuery()
        if (adapter.gameCount == 0) swipe.isRefreshing = true else adapter.setFooterLoading(true)

        executor.execute {
            val found = ArrayList<Game>()
            var p = startPage
            var next = true
            var error: String? = null
            var tries = 0
            try {
                // Keep fetching until we have a decent batch after filtering
                while (next && found.size < 15 && tries < 6) {
                    val r = RawgApi.games(key, q.from, q.to, q.platforms, q.ordering, p)
                    for (g in r.games) {
                        if (g.released == null) continue
                        if (g.platformIds.isEmpty()) continue
                        if (q.hideObscure && g.added < MIN_ADDED) continue
                        found.add(g)
                    }
                    next = r.hasNext
                    // Sorted by popularity: once we hit obscure titles, everything after is obscure too
                    if (q.popular && q.hideObscure && r.games.isNotEmpty() && r.games.last().added < MIN_ADDED) {
                        next = false
                    }
                    p++
                    tries++
                }
            } catch (e: RawgApi.ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't load games. Check your internet connection and pull down to retry."
            }

            val err = error
            val nextPage = p
            val more = next
            main.post {
                if (gen != generation || isDestroyed) return@post
                loading = false
                swipe.isRefreshing = false
                adapter.setFooterLoading(false)
                if (err != null) {
                    if (adapter.gameCount == 0) showStatus(err)
                    else Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return@post
                }
                page = nextPage
                hasNext = more
                adapter.addGames(found)
                if (adapter.gameCount == 0 && !hasNext) {
                    showStatus("No games found for this filter.")
                } else {
                    status.visibility = View.GONE
                }
                recycler.post { maybeLoadMore() }
            }
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.setOnClickListener(null)
        status.visibility = View.VISIBLE
    }

    private fun showMissingKey() {
        status.text = "Game Radar needs a free RAWG API key to load games.\n\nTap here to add it."
        status.setOnClickListener { showKeyDialog() }
        status.visibility = View.VISIBLE
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste your RAWG API key"
            setText(Prefs.key(this@MainActivity))
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("RAWG API key")
            .setMessage(
                "Game Radar gets its release data from RAWG.io. Get a free key at rawg.io/apidocs " +
                    "(sign up, then copy the key), and paste it here."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setKey(this, input.text.toString().trim())
                reload()
            }
            .setNeutralButton("Get a key") { _, _ -> openUrl("https://rawg.io/apidocs") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** Minimum number of RAWG users tracking a game for it to count as "not obscure". */
        private const val MIN_ADDED = 3
    }
}
