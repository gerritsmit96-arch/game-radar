package com.gerrit.gameradar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object Prefs {
    private const val FILE = "gameradar"
    private const val KEY = "rawg_key"

    fun key(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun setKey(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}

object Fmt {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    fun parse(s: String?): LocalDate? =
        try { if (s == null) null else LocalDate.parse(s) } catch (e: Exception) { null }

    fun monthHeader(g: Game): String =
        parse(g.released)?.format(monthFmt) ?: "Date unknown"

    fun dateLine(g: Game): String {
        val d = parse(g.released) ?: return "TBA"
        val base = d.format(dayFmt)
        val rel = relative(d)
        val est = if (g.tba) " (estimated)" else ""
        return "$base$est · $rel"
    }

    fun relative(d: LocalDate): String {
        val days = ChronoUnit.DAYS.between(LocalDate.now(), d)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days == -1L -> "Yesterday"
            days < 0L -> "${-days} days ago"
            days < 60L -> "in $days days"
            days < 730L -> "in ${days / 30} months"
            else -> "in ${days / 365} years"
        }
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

/** Fills a row with coloured platform badges (PC / PS5 / Xbox). */
fun LinearLayout.showPlatformBadges(platformIds: List<Int>) {
    removeAllViews()
    for (id in platformIds) {
        val p = RawgApi.platform(id) ?: continue
        val tv = TextView(context).apply {
            text = p.label
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(context.dp(7), context.dp(2), context.dp(7), context.dp(2))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(4).toFloat()
                setColor(p.color)
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = context.dp(5)
        addView(tv, lp)
    }
}

/** Tiny image loader so the app needs no extra libraries. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bmp: Bitmap? = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                try {
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                cache.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }
}
