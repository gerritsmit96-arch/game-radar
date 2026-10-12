package com.gerrit.gameradar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class Platform(val id: Int, val label: String, val color: Int)

/**
 * One row in the list: a game releasing on a given date, on one or more platforms.
 * The same game can appear twice if it launches on different dates per platform
 * (e.g. PC this month, consoles next year).
 */
data class Game(
    val key: String,
    val id: Int,
    val name: String,
    val slug: String,
    val igdbUrl: String?,
    val date: LocalDate,
    val human: String,
    val platformIds: ArrayList<Int>,
    val genres: ArrayList<String>,
    val coverId: String?,
    val screenshotId: String?,
    val summary: String,
    val websites: ArrayList<String>,
    val hypes: Int,
    val ratingCount: Int
) : Serializable {
    val score: Int get() = hypes + ratingCount

    /** IGDB uses "Oct 14, 2026" for exact days and things like "Q4 2026" or "2027" otherwise. */
    val exactDay: Boolean get() = Regex("^[A-Za-z]{3} \\d{1,2}, \\d{4}$").matches(human)
}

/** A single release_dates row from IGDB (one game, one platform, one date). */
class ReleaseRow(
    val gameId: Int,
    val platform: Int,
    val date: LocalDate,
    val human: String,
    val gameType: Int?,
    val game: JSONObject
)

class ReleasePage(val rows: List<ReleaseRow>, val rawCount: Int)

enum class PlatformFilter(val param: String) {
    ALL("6,167,169,49"),
    PC("6"),
    PS5("167"),
    XBOX("169,49")
}

object IgdbApi {
    private const val BASE = "https://api.igdb.com/v4"
    const val PAGE_SIZE = 500

    /** IGDB platform ids, in display order. */
    val PLATFORMS = listOf(
        Platform(6, "PC", 0xFF5F6B7A.toInt()),
        Platform(167, "PS5", 0xFF2E6BE6.toInt()),
        Platform(169, "Xbox Series X|S", 0xFF1F9D3A.toInt()),
        Platform(49, "Xbox One", 0xFF4C7A4E.toInt())
    )
    val PLATFORM_ORDER = PLATFORMS.map { it.id }

    /** IGDB game types that are not standalone games (DLC, bundles, mods, updates...). */
    private val SKIP_TYPES = setOf(1, 3, 5, 6, 7, 12, 13, 14)

    private const val BASE_FIELDS =
        "date,human,platform,game.id," +
            "game.name,game.slug,game.url,game.summary,game.hypes,game.total_rating_count," +
            "game.cover.image_id,game.screenshots.image_id,game.genres.name,game.websites.url"

    /** Set to false if IGDB ever rejects the game_type field, so we stop asking for it. */
    @Volatile private var useGameType = true

    class ApiException(message: String, val code: Int = 0) : Exception(message)

    fun platform(id: Int): Platform? = PLATFORMS.firstOrNull { it.id == id }

    fun coverUrl(id: String?): String? =
        id?.let { "https://images.igdb.com/igdb/image/upload/t_cover_big/$it.jpg" }

    fun screenshotUrl(id: String?): String? =
        id?.let { "https://images.igdb.com/igdb/image/upload/t_screenshot_big/$it.jpg" }

    /** Fetches one page of release dates in [from, to], sorted by date. */
    fun releases(
        ctx: Context,
        from: LocalDate,
        to: LocalDate,
        platforms: String,
        ascending: Boolean,
        offset: Int
    ): ReleasePage {
        val fromTs = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val toTs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
        val dir = if (ascending) "asc" else "desc"

        fun body(withType: Boolean): String {
            val fields = if (withType) "$BASE_FIELDS,game.game_type" else BASE_FIELDS
            return "fields $fields; " +
                "where date >= $fromTs & date <= $toTs & platform = ($platforms); " +
                "sort date $dir; limit $PAGE_SIZE; offset $offset;"
        }

        val arr: JSONArray = try {
            postArray(ctx, "release_dates", body(useGameType))
        } catch (e: ApiException) {
            if (e.code == 400 && useGameType) {
                useGameType = false
                postArray(ctx, "release_dates", body(false))
            } else throw e
        }

        val out = ArrayList<ReleaseRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val game = o.optJSONObject("game") ?: continue
            if (!o.has("date") || o.isNull("date")) continue
            val date = Instant.ofEpochSecond(o.optLong("date")).atZone(ZoneOffset.UTC).toLocalDate()
            val type = if (game.has("game_type") && !game.isNull("game_type")) game.optInt("game_type") else null
            if (type != null && type in SKIP_TYPES) continue
            out.add(
                ReleaseRow(
                    gameId = game.optInt("id"),
                    platform = o.optInt("platform"),
                    date = date,
                    human = o.str("human") ?: date.toString(),
                    gameType = type,
                    game = game
                )
            )
        }
        return ReleasePage(out, arr.length())
    }

    /** Builds a list entry from a release row. */
    fun toGame(r: ReleaseRow): Game {
        val g = r.game
        val genres = ArrayList<String>()
        g.optJSONArray("genres")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.str("name")?.let { genres.add(it) }
        }
        val sites = ArrayList<String>()
        g.optJSONArray("websites")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.str("url")?.let { sites.add(it) }
        }
        val shot = g.optJSONArray("screenshots")?.optJSONObject(0)?.str("image_id")
        return Game(
            key = "${r.gameId}-${r.date}",
            id = r.gameId,
            name = g.optString("name"),
            slug = g.optString("slug"),
            igdbUrl = g.str("url"),
            date = r.date,
            human = r.human,
            platformIds = arrayListOf(r.platform),
            genres = genres,
            coverId = g.optJSONObject("cover")?.str("image_id"),
            screenshotId = shot,
            summary = g.str("summary").orEmpty(),
            websites = sites,
            hypes = g.optInt("hypes"),
            ratingCount = g.optInt("total_rating_count")
        )
    }

    // ---- networking & auth ----

    private fun postArray(ctx: Context, endpoint: String, body: String): JSONArray {
        val clientId = Prefs.clientId(ctx)
        var token = validToken(ctx)
        var res = post(endpoint, body, clientId, token)
        if (res.first == 401) {
            // Token expired or revoked: get a fresh one and try once more
            Prefs.setToken(ctx, "", 0)
            token = validToken(ctx)
            res = post(endpoint, body, clientId, token)
        }
        val (code, text) = res
        if (code == 401 || code == 403) {
            throw ApiException("IGDB rejected your Twitch Client ID / Secret. Tap ⋮ → IGDB keys and check them.", code)
        }
        if (code == 429) throw ApiException("IGDB is busy (too many requests). Pull down to retry in a moment.", code)
        if (code !in 200..299) {
            throw ApiException("IGDB error $code: ${text.take(300)}", code)
        }
        return JSONArray(text)
    }

    private fun post(endpoint: String, body: String, clientId: String, token: String): Pair<Int, String> {
        val conn = URL("$BASE/$endpoint").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Client-ID", clientId)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Returns a cached Twitch app token, or requests a new one (they last ~60 days). */
    private fun validToken(ctx: Context): String {
        val cached = Prefs.token(ctx)
        if (cached.isNotBlank() && System.currentTimeMillis() < Prefs.tokenExpiry(ctx)) return cached

        val id = URLEncoder.encode(Prefs.clientId(ctx), "UTF-8")
        val secret = URLEncoder.encode(Prefs.clientSecret(ctx), "UTF-8")
        val conn = URL(
            "https://id.twitch.tv/oauth2/token?client_id=$id&client_secret=$secret&grant_type=client_credentials"
        ).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(
                    "Twitch didn't accept your Client ID / Secret ($code). Tap ⋮ → IGDB keys and check them.",
                    401
                )
            }
            val json = JSONObject(text)
            val token = json.optString("access_token")
            val expiresIn = json.optLong("expires_in", 3600)
            // Refresh a day early
            Prefs.setToken(ctx, token, System.currentTimeMillis() + (expiresIn - 86_400).coerceAtLeast(600) * 1000)
            return token
        } finally {
            conn.disconnect()
        }
    }

    /** optString returns the text "null" for JSON nulls; this returns a real null. */
    private fun JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
}
