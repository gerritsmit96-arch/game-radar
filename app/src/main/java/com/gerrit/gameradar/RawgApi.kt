package com.gerrit.gameradar

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class Platform(val id: Int, val label: String, val color: Int)

data class Game(
    val id: Int,
    val slug: String,
    val name: String,
    val released: String?,
    val tba: Boolean,
    val image: String?,
    val platformIds: ArrayList<Int>,
    val genres: ArrayList<String>,
    val storeIds: ArrayList<Int>,
    val added: Int
) : Serializable

data class StoreLink(val storeId: Int, val url: String)

data class GameDetail(val description: String, val website: String?)

enum class PlatformFilter(val param: String) {
    ALL("4,187,186,1"),
    PC("4"),
    PS5("187"),
    XBOX("186,1")
}

object RawgApi {
    private const val BASE = "https://api.rawg.io/api"

    /** Only the platforms this app cares about, in display order. */
    val PLATFORMS = listOf(
        Platform(4, "PC", 0xFF5F6B7A.toInt()),
        Platform(187, "PS5", 0xFF2E6BE6.toInt()),
        Platform(186, "Xbox Series X|S", 0xFF1F9D3A.toInt()),
        Platform(1, "Xbox One", 0xFF4C7A4E.toInt())
    )
    private val PLATFORM_ORDER = PLATFORMS.map { it.id }

    /** RAWG store ids for PC / PlayStation / Xbox storefronts. */
    val STORES = linkedMapOf(
        1 to "Steam",
        3 to "PlayStation Store",
        2 to "Xbox Store",
        11 to "Epic Games Store",
        5 to "GOG",
        9 to "itch.io",
        7 to "Xbox 360 Store"
    )

    class ApiException(message: String) : Exception(message)

    data class PageResult(val games: List<Game>, val hasNext: Boolean)

    fun platform(id: Int): Platform? = PLATFORMS.firstOrNull { it.id == id }

    fun games(
        key: String,
        from: LocalDate,
        to: LocalDate,
        platforms: String,
        ordering: String,
        page: Int
    ): PageResult {
        val url = "$BASE/games?key=${Uri.encode(key)}" +
            "&dates=$from,$to" +
            "&platforms=$platforms" +
            "&ordering=$ordering" +
            "&page=$page&page_size=40"
        val json = get(url)
        val arr = json.optJSONArray("results") ?: JSONArray()
        val list = ArrayList<Game>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(parseGame(o))
        }
        return PageResult(list, !json.isNull("next"))
    }

    fun details(key: String, id: Int): GameDetail {
        val json = get("$BASE/games/$id?key=${Uri.encode(key)}")
        return GameDetail(
            description = json.str("description_raw")?.trim().orEmpty(),
            website = json.str("website")?.takeIf { it.isNotBlank() }
        )
    }

    fun stores(key: String, id: Int): List<StoreLink> {
        val json = get("$BASE/games/$id/stores?key=${Uri.encode(key)}")
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<StoreLink>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(StoreLink(o.optInt("store_id"), o.str("url").orEmpty()))
        }
        return out
    }

    /** RAWG can serve smaller cropped images, which saves a lot of mobile data. */
    fun thumb(url: String?, w: Int = 600, h: Int = 400): String? {
        if (url == null) return null
        return url
            .replace("/media/games/", "/media/crop/$w/$h/games/")
            .replace("/media/screenshots/", "/media/crop/$w/$h/screenshots/")
    }

    private fun parseGame(o: JSONObject): Game {
        val platformSet = HashSet<Int>()
        o.optJSONArray("platforms")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pid = arr.optJSONObject(i)?.optJSONObject("platform")?.optInt("id") ?: continue
                platformSet.add(pid)
            }
        }
        val platformIds = ArrayList(PLATFORM_ORDER.filter { it in platformSet })

        val genres = ArrayList<String>()
        o.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.str("name")?.let { genres.add(it) }
            }
        }

        val storeIds = ArrayList<Int>()
        o.optJSONArray("stores")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sid = arr.optJSONObject(i)?.optJSONObject("store")?.optInt("id") ?: continue
                storeIds.add(sid)
            }
        }

        return Game(
            id = o.optInt("id"),
            slug = o.optString("slug"),
            name = o.optString("name"),
            released = o.str("released"),
            tba = o.optBoolean("tba"),
            image = o.str("background_image"),
            platformIds = platformIds,
            genres = genres,
            storeIds = storeIds,
            added = o.optInt("added")
        )
    }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "GameRadar/1.0 (Android)")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) {
                throw ApiException("RAWG rejected the API key. Tap ⋮ → API key and check it.")
            }
            if (code !in 200..299) {
                throw ApiException("RAWG returned an error ($code). Try again in a moment.")
            }
            val json = JSONObject(body)
            if (json.has("error")) {
                throw ApiException("RAWG: " + json.optString("error"))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /** optString returns the text "null" for JSON nulls; this returns a real null. */
    private fun JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k)
}
