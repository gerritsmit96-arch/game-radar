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

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun clientId(ctx: Context): String = sp(ctx).getString("client_id", "") ?: ""
    fun clientSecret(ctx: Context): String = sp(ctx).getString("client_secret", "") ?: ""
    fun hasKeys(ctx: Context): Boolean = clientId(ctx).isNotBlank() && clientSecret(ctx).isNotBlank()

    fun setKeys(ctx: Context, id: String, secret: String) {
        sp(ctx).edit()
            .putString("client_id", id)
            .putString("client_secret", secret)
            .putString("token", "")
            .putLong("token_expiry", 0)
            .apply()
    }

    fun token(ctx: Context): String = sp(ctx).getString("token", "") ?: ""
    fun tokenExpiry(ctx: Context): Long = sp(ctx).getLong("token_expiry", 0)
    fun setToken(ctx: Context, token: String, expiry: Long) {
        sp(ctx).edit().putString("token", token).putLong("token_expiry", expiry).apply()
    }
}

object Fmt {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    fun monthHeader(g: Game): String =
        if (g.exactDay) g.date.format(monthFmt) else "${g.human} (no exact date yet)"

    fun dateLine(g: Game): String =
        if (g.exactDay) "${g.date.format(dayFmt)} · ${relative(g.date)}"
        else "${g.human} · exact day not announced"

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

/** Opens a link in a web browser, never in an app that claims the link (e.g. the Twitch app). */
fun Context.openInBrowser(url: String) {
    val uri = Uri.parse(url)
    val browsers = listOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
        "com.microsoft.emmx", "com.opera.browser", "com.brave.browser")
    for (pkg in browsers) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
            return
        } catch (e: ActivityNotFoundException) { }
    }
    try {
        val i = Intent(Intent.ACTION_VIEW, uri)
        i.selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        startActivity(i)
        return
    } catch (e: Exception) { }
    openUrl(url)
}

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
        val p = IgdbApi.platform(id) ?: continue
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
