package eu.kanade.tachiyomi.animeextension.tr.turkanime

import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.pixeldrainextractor.PixelDrainExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

@Source
abstract class TurkAnime : AnimeHttpLegacySource() {

    override val supportsLatest = false

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val archiveMirrors = listOf(
        "https://gitlab.com/AnimeDepo/animedepo/-/raw/master",
        "https://raw.githubusercontent.com/barkeser2002/turkanime-gui/main/arsiv",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    @Volatile
    private var cachedDizin: List<Pair<String, String>>? = null

    private fun fetchArchiveJson(relPath: String): JsonElement {
        val clean = relPath.trimStart('/')
        for (base in archiveMirrors) {
            val url = "$base/$clean"
            val result = runCatching {
                client.newCall(GET(url, headers)).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    json.parseToJsonElement(resp.body.string())
                }
            }.getOrNull()
            if (result != null) return result
        }
        throw Exception("TürkAnime Arşiv dosyası okunamadı: $clean")
    }

    private fun loadDizinList(): List<Pair<String, String>> {
        cachedDizin?.let { return it }
        val dizin = fetchArchiveJson("dizin.json").jsonObject
        val index = dizin["index"]?.jsonObject ?: throw Exception("TürkAnime dizin formatı geçersiz")
        val list = mutableListOf<Pair<String, String>>()
        for ((_, items) in index) {
            val map = items.jsonObject
            for ((slug, info) in map) {
                val title = info.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: slug
                list.add(slug to title)
            }
        }
        list.sortBy { it.second.lowercase() }
        cachedDizin = list
        return list
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val all = loadDizinList()
        val pageSize = 30
        val chunks = all.chunked(pageSize)
        if (page - 1 >= chunks.size) return AnimesPage(emptyList(), false)
        val items = chunks[page - 1].map { (slug, title) ->
            SAnime.create().apply {
                url = slug
                this.title = title
            }
        }
        return AnimesPage(items, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val all = loadDizinList()
        val qNorm = normalizeTr(query)
        val words = qNorm.split(" ").filter { it.isNotEmpty() }

        val scored = mutableListOf<Pair<Int, SAnime>>()
        for ((slug, title) in all) {
            val tNorm = normalizeTr(title)
            val sNorm = normalizeTr(slug)
            val score = when {
                tNorm == qNorm -> 1000
                tNorm.startsWith(qNorm) -> 800
                tNorm.contains(qNorm) || sNorm.contains(qNorm) -> 600
                words.isNotEmpty() && words.all { w -> tNorm.contains(w) || sNorm.contains(w) } -> 400
                else -> continue
            }
            scored.add(
                score to SAnime.create().apply {
                    url = slug
                    this.title = title
                },
            )
        }

        scored.sortWith(compareByDescending<Pair<Int, SAnime>> { it.first }.thenBy { it.second.title.length })
        return AnimesPage(scored.take(25).map { it.second }, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return SAnime.create().apply {
            url = anime.url
            title = anime.title
            initialized = true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.trim('/')
        val arr = fetchArchiveJson("animeler/$slug/bolumler.json").jsonArray
        val episodes = mutableListOf<SEpisode>()
        arr.forEachIndexed { i, entry ->
            val pair = entry.jsonArray
            val epSlug = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: ""
            val epTitle = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "Bölüm"
            if (epSlug.isNotEmpty()) {
                episodes.add(
                    SEpisode.create().apply {
                        url = "$slug/$epSlug"
                        name = epTitle
                        episode_number = (i + 1).toFloat()
                    },
                )
            }
        }
        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/", limit = 2)
        if (parts.size < 2) throw Exception("Geçersiz TürkAnime bölüm kimliği")
        val (animeSlug, epSlug) = parts

        val arr = fetchArchiveJson("animeler/$animeSlug/$epSlug.json").jsonArray
        val candidates = mutableListOf<StreamCandidate>()
        val seen = HashSet<Pair<String, String>>()

        for (item in arr) {
            val obj = item.jsonObject
            val player = (obj["player"]?.jsonPrimitive?.contentOrNull ?: "Oynatıcı").trim().uppercase()
            if (player.startsWith("DEAD_")) continue
            if (obj["alive"]?.jsonPrimitive?.booleanOrNull == false) continue

            val rawUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val clean = cleanUrl(rawUrl) ?: continue

            val fansubRaw = (obj["fansub"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            val fansubName = fansubRaw.ifEmpty { "Varsayılan" }
            if (!seen.add(clean to fansubName)) continue

            val referer = obj["referer"]?.jsonPrimitive?.contentOrNull ?: when {
                clean.contains("sibnet.ru") -> "https://video.sibnet.ru/"
                clean.contains(".mp4") || clean.contains(".m3u8") -> "https://www.turkanime.co/"
                else -> null
            }

            candidates.add(
                StreamCandidate(
                    priority = playerPriority(player),
                    player = player,
                    url = clean,
                    referer = referer,
                    fansub = fansubName,
                ),
            )
        }

        candidates.sortBy { it.priority }

        val videos = candidates.take(6).parallelCatchingFlatMapBlocking { c ->
            extractCandidate(c)
        }

        if (videos.isEmpty()) {
            throw Exception("Bu bölüm için çalışan arşiv bağlantısı bulunamadı")
        }
        return videos
    }

    private fun extractCandidate(c: StreamCandidate): List<Video> = runCatching {
        val prefix = "${c.player} (${c.fansub}) - "
        val url = c.url
        when {
            url.contains("pixeldrain.com") ->
                PixelDrainExtractor().videosFromUrl(url, prefix)

            url.contains("sibnet.ru") ->
                SibnetExtractor(client).videosFromUrl(url, prefix)

            url.contains("ok.ru") || url.contains("odnoklassniki") ->
                runBlocking { OkruExtractor(client, headers).videosFromUrl(url, prefix) }

            url.contains("dailymotion.com") || url.contains("dai.ly") ->
                DailymotionExtractor(client, headers).videosFromUrl(url, prefix)

            url.contains("vk.com") || url.contains("vkvideo.ru") ->
                runBlocking { VkExtractor(client, headers).videosFromUrl(url, prefix) }

            url.contains("vidmoly") ->
                runBlocking { VidMolyExtractor(client, headers).videosFromUrl(url, prefix) }

            url.contains("sendvid.com") ->
                SendvidExtractor(client, headers).videosFromUrl(url, prefix)

            url.contains("drive.google.com") ->
                GoogleDriveExtractor(client, headers).videosFromUrl(url, "${prefix}GDrive")

            url.contains(".m3u8") ->
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = c.referer ?: "https://www.turkanime.co/",
                    videoNameGen = { q -> "$prefix$q" },
                )

            url.contains(".mp4") -> {
                val vidHeaders = if (c.referer != null) {
                    headersBuilder().set("Referer", c.referer).build()
                } else {
                    headers
                }
                listOf(Video(videoUrl = url, videoTitle = "${c.player} (${c.fansub})", headers = vidHeaders))
            }

            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun cleanUrl(raw: String): String? {
        var u = raw.trim()
        run cleanLoop@{
            repeat(5) {
                if (u.startsWith("//")) u = "https:$u"
                val lower = u.lowercase()
                if (lower.startsWith("https:http://") || lower.startsWith("https:https://")) {
                    u = u.substring(6)
                    return@repeat
                }
                if (lower.startsWith("http:http://") || lower.startsWith("http:https://")) {
                    u = u.substring(5)
                    return@repeat
                }
                val parsed = u.toHttpUrlOrNull()
                if (parsed != null) {
                    val host = parsed.host.lowercase()
                    if ((host == "href.li" || host == "www.href.li") && parsed.encodedQuery != null) {
                        u = runCatching { URLDecoder.decode(parsed.encodedQuery!!, "UTF-8") }
                            .getOrDefault(parsed.encodedQuery!!)
                        return@repeat
                    }
                }
                return@cleanLoop
            }
        }

        val parsed = u.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (host.contains("turkanime")) {
            if (parsed.encodedPath.trimEnd('/').endsWith("/pixeldrain.php")) {
                val id = parsed.queryParameter("id")
                if (!id.isNullOrBlank()) return "https://pixeldrain.com/u/$id"
            }
            return null
        }
        return u
    }

    private fun playerPriority(player: String): Int {
        val norm = when (player) {
            "OK.RU" -> "ODNOKLASSNIKI"
            "XDVID" -> "HDVID"
            else -> player
        }
        val supported = listOf(
            "YADISK",
            "ALUCARD(BETA)",
            "GDRIVE",
            "MAIL",
            "PIXELDRAIN",
            "AMATERASU(BETA)",
            "HDVID",
            "ODNOKLASSNIKI",
            "DAILYMOTION",
            "SIBNET",
            "VK",
            "VIDMOLY",
            "SENDVID",
            "MYVI",
        )
        val pos = supported.indexOf(norm)
        if (pos >= 0) return pos
        val last = setOf("MP4UPLOAD", "CLONE", "STREAMSB", "YOURUPLOAD", "UQLOAD")
        return if (norm in last) 1000 else 500
    }

    private fun normalizeTr(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                'ç', 'Ç' -> out.append('c')
                'ğ', 'Ğ' -> out.append('g')
                'ı', 'İ' -> out.append('i')
                'ö', 'Ö' -> out.append('o')
                'ş', 'Ş' -> out.append('s')
                'ü', 'Ü' -> out.append('u')
                else -> {
                    if (ch.isLetterOrDigit() && ch.code < 128) {
                        out.append(ch.lowercaseChar())
                    } else if (ch.isWhitespace() || ch == '-' || ch == '_') {
                        out.append(' ')
                    }
                }
            }
        }
        return out.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private data class StreamCandidate(
        val priority: Int,
        val player: String,
        val url: String,
        val referer: String?,
        val fansub: String,
    )
}
