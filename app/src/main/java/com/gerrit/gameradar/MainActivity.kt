package com.gerrit.gameradar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
    private var offset = 0
    private var hasNext = true
    private var loading = false
    private var generation = 0

    // Entries merged by game + date; (game, platform) pairs already placed
    private val entries = LinkedHashMap<String, Game>()
    private val placed = HashSet<String>()

    private class Query(
        val from: LocalDate,
        val to: LocalDate,
        val platforms: String,
        val ascending: Boolean,
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

        findViewById<TextView>(R.id.attribution).setOnClickListener { openUrl("https://www.igdb.com") }

        if (!Prefs.hasKeys(this)) {
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
            Query(today.plusDays(1), today.plusYears(5), platform.param, true, popularFirst, hideObscure)
        } else {
            Query(today.minusDays(60), today, platform.param, false, popularFirst, hideObscure)
        }
    }

    private fun reload() {
        generation++
        loading = false
        offset = 0
        hasNext = true
        entries.clear()
        placed.clear()
        adapter.setGames(emptyList(), groupByMonth = !popularFirst)
        status.visibility = View.GONE
        loadMore()
    }

    private fun maybeLoadMore() {
        if (!hasNext || loading || popularFirst) return
        val last = layoutManager.findLastVisibleItemPosition()
        if (last >= adapter.itemCount - 8) loadMore()
    }

    private fun loadMore() {
        if (!Prefs.hasKeys(this)) {
            swipe.isRefreshing = false
            showMissingKey()
            return
        }
        if (loading || !hasNext) return
        loading = true

        val gen = generation
        val startOffset = offset
        val q = currentQuery()
        if (adapter.gameCount == 0) swipe.isRefreshing = true else adapter.setFooterLoading(true)

        executor.execute {
            val rows = ArrayList<ReleaseRow>()
            var off = startOffset
            var next = true
            var error: String? = null
            try {
                // "Popular first" needs the whole range to sort it; otherwise load until we have a useful batch.
                val maxPages = if (q.popular) 12 else 4
                var pages = 0
                var useful = 0
                while (next && pages < maxPages && (q.popular || useful < 20)) {
                    val page = IgdbApi.releases(this, q.from, q.to, q.platforms, q.ascending, off)
                    for (r in page.rows) {
                        if (q.hideObscure && r.game.optInt("hypes") + r.game.optInt("total_rating_count") == 0) continue
                        rows.add(r)
                        useful++
                    }
                    // IGDB doesn't report totals: a full page means there may be more
                    next = page.rawCount >= IgdbApi.PAGE_SIZE
                    off += IgdbApi.PAGE_SIZE
                    pages++
                }
            } catch (e: IgdbApi.ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't load games. Check your internet connection and pull down to retry."
            }

            val err = error
            val nextOffset = off
            val more = if (q.popular) false else next
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
                offset = nextOffset
                hasNext = more
                merge(rows)
                val list = if (q.popular) entries.values.sortedByDescending { it.score } else entries.values.toList()
                adapter.setGames(list, groupByMonth = !q.popular)
                if (adapter.gameCount == 0 && !hasNext) {
                    showStatus("No games found for this filter.")
                } else {
                    status.visibility = View.GONE
                }
                recycler.post { maybeLoadMore() }
            }
        }
    }

    /**
     * Folds raw release rows into list entries: one entry per game per date, listing every
     * platform launching that day. Regional duplicates of the same game+platform are skipped.
     */
    private fun merge(rows: List<ReleaseRow>) {
        for (r in rows) {
            if (r.gameId == 0) continue
            if (!placed.add("${r.gameId}-${r.platform}")) continue
            val key = "${r.gameId}-${r.date}"
            val existing = entries[key]
            if (existing == null) {
                entries[key] = IgdbApi.toGame(r)
            } else if (r.platform !in existing.platformIds) {
                existing.platformIds.add(r.platform)
                existing.platformIds.sortBy { IgdbApi.PLATFORM_ORDER.indexOf(it) }
            }
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.setOnClickListener(null)
        status.visibility = View.VISIBLE
    }

    private fun showMissingKey() {
        status.text = "Game Radar needs free IGDB keys (a Twitch Client ID and Secret) to load games.\n\nTap here to add them."
        status.setOnClickListener { showKeyDialog() }
        status.visibility = View.VISIBLE
    }

    private fun showKeyDialog() {
        val idInput = EditText(this).apply {
            hint = "Client ID"
            setText(Prefs.clientId(this@MainActivity))
            isSingleLine = true
        }
        val secretInput = EditText(this).apply {
            hint = "Client Secret"
            setText(Prefs.clientSecret(this@MainActivity))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(idInput)
            addView(secretInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("IGDB keys")
            .setMessage(
                "Release dates come from IGDB (run by Twitch), which tracks each platform's date separately.\n\n" +
                    "1. Tap \"Open Twitch\" and log in (2-factor login must be on).\n" +
                    "2. Register Your Application: any name, OAuth Redirect URL http://localhost, " +
                    "category Application Integration, client type Confidential.\n" +
                    "3. Tap Manage, copy the Client ID, press New Secret and copy that too."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setKeys(this, idInput.text.toString().trim(), secretInput.text.toString().trim())
                reload()
            }
            .setNeutralButton("Open Twitch") { _, _ -> openUrl("https://dev.twitch.tv/console/apps") }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
