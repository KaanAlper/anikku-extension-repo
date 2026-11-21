package eu.kanade.tachiyomi.animeextension.tr.anizle

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class Anizle : AnimeHttpLegacySource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")

    private val mirrors = listOf("https://anizle.co", "https://anizm.com.tr", "https://anizm.pro")
    private val json = Json { ignoreUnknownKeys = true }

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    @Volatile
    private var cachedCatalog: List<JsonObject>? = null

    private fun fetchFromMirrors(path: String): Pair<String, String> {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        for (base in mirrors) {
            val url = "$base$cleanPath"
            val result = runCatching {
                client.newCall(GET(url, headers)).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val text = resp.body.string()
                    if (text.contains("Just a moment") || text.contains("Checking your browser")) {
                        return@use null
                    }
                    base to text
                }
            }.getOrNull()
            if (result != null) return result
        }
        throw Exception("Anizle aynalarına ulaşılamadı: $cleanPath")
    }

    private fun loadCatalog(): List<JsonObject> {
        cachedCatalog?.let { return it }
        val (_, body) = fetchFromMirrors("/getAnimeListForSearch")
        val parsed = json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        cachedCatalog = parsed
        return parsed
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val catalog = loadCatalog()
        val pageSize = 30
        val chunks = catalog.chunked(pageSize)
        if (page - 1 >= chunks.size) return AnimesPage(emptyList(), false)
        val items = chunks[page - 1].mapNotNull { catalogItemToAnime(it) }
        return AnimesPage(items, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val catalog = loadCatalog()
        val qNorm = normalizeTr(query)
        val words = qNorm.split(" ").filter { it.isNotEmpty() }

        val scored = mutableListOf<Pair<Int, SAnime>>()
        for (item in catalog) {
            val title = item["info_title"]?.jsonPrimitive?.contentOrNull ?: ""
            val slug = item["info_slug"]?.jsonPrimitive?.contentOrNull ?: ""
            if (title.isEmpty() || slug.isEmpty()) continue

            val tNorm = normalizeTr(title)
            val origNorm = item["info_titleoriginal"]?.jsonPrimitive?.contentOrNull?.let(::normalizeTr) ?: ""

            val score = when {
                tNorm == qNorm || origNorm == qNorm -> 1000
                tNorm.startsWith(qNorm) -> 800
                tNorm.contains(qNorm) || origNorm.contains(qNorm) -> 600
                words.isNotEmpty() && words.all { w -> tNorm.contains(w) || origNorm.contains(w) } -> 400
                else -> continue
            }

            val anime = catalogItemToAnime(item) ?: continue
            scored.add(score to anime)
        }

        scored.sortWith(compareByDescending<Pair<Int, SAnime>> { it.first }.thenBy { it.second.title.length })
        val results = scored.take(25).map { it.second }
        return AnimesPage(results, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun catalogItemToAnime(item: JsonObject): SAnime? {
        val title = item["info_title"]?.jsonPrimitive?.contentOrNull ?: return null
        val slug = item["info_slug"]?.jsonPrimitive?.contentOrNull ?: return null
        if (title.isEmpty() || slug.isEmpty()) return null
        val thumb = item["info_poster"]?.jsonPrimitive?.contentOrNull
            ?: item["thumbnail"]?.jsonPrimitive?.contentOrNull
        return SAnime.create().apply {
            url = "/$slug"
            this.title = title
            thumbnail_url = thumb?.let {
                if (it.startsWith("http")) it else "$baseUrl/storage/pcovers/$it"
            }
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (origin, html) = fetchFromMirrors(anime.url)
        val doc = Jsoup.parse(html, origin)
        return SAnime.create().apply {
            url = anime.url
            title = doc.selectFirst("h2.anizm_pageTitle, h1")?.text()?.takeIf { it.isNotBlank() } ?: anime.title
            thumbnail_url = doc.selectFirst("div.infoPosterImg > img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
                ?: anime.thumbnail_url
            description = doc.selectFirst("div.infoDesc, div.description")?.text()
            genre = doc.select("span.dataValue > span.tag > span.label").eachText().joinToString(", ")
            initialized = true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (_, html) = fetchFromMirrors(anime.url)
        val re = Regex("""href="(?:https?://[^/]+)?/([^"]+?-(\d+)-bolum[^"]*)"""")
        val seen = HashSet<Int>()
        val eps = mutableListOf<Triple<Int, String, String>>()

        for (match in re.findAll(html)) {
            val epSlug = match.groupValues[1].trim('/')
            val num = match.groupValues[2].toIntOrNull() ?: continue
            if (seen.add(num)) {
                eps.add(Triple(num, epSlug, "$num. Bölüm"))
            }
        }

        eps.sortBy { it.first }
        if (eps.isEmpty()) {
            throw Exception("Anizle üzerinde bölüm bulunamadı: ${anime.title}")
        }

        return eps.reversed().map { (num, id, title) ->
            SEpisode.create().apply {
                url = "/$id"
                name = title
                episode_number = num.toFloat()
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (origin, html) = fetchFromMirrors(episode.url)

        val v3Re = Regex("""data-translator-id="(\d+)"[^>]*data-sources="([^"]+/bolum/\d+/cevirmen/\d+)"""")
        var translators = v3Re.findAll(html).map {
            "Çevirmen #${it.groupValues[1]}" to it.groupValues[2]
        }.toList()

        if (translators.isEmpty()) {
            val v3Alt = Regex("""data-sources="([^"]+/bolum/\d+/cevirmen/\d+)"""")
            translators = v3Alt.findAll(html).mapIndexed { i, m ->
                "Çevirmen #${i + 1}" to m.groupValues[1]
            }.toList()
        }

        if (translators.isEmpty()) {
            val oldRe = Regex("""translator="([^"]+)"""")
            translators = oldRe.findAll(html).mapIndexed { i, m ->
                "Fansub #${i + 1}" to m.groupValues[1]
            }.toList()
        }

        val ajaxHeaders = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json")
            .set("Referer", "$origin/")
            .build()

        val jobs = mutableListOf<Triple<String, String, String>>()
        for ((fansubName, trUrl) in translators.take(3)) {
            val fullTrUrl = if (trUrl.startsWith("http")) trUrl else "$origin/${trUrl.trimStart('/')}"
            val jsonBody = runCatching {
                client.newCall(GET(fullTrUrl, ajaxHeaders)).awaitSuccess().use { it.body.string() }
            }.getOrNull() ?: continue

            val videos = runCatching {
                json.parseToJsonElement(jsonBody).jsonObject["videos"]?.jsonArray
            }.getOrNull() ?: continue

            for (v in videos) {
                val obj = v.jsonObject
                val vidId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Player"
                val lower = name.lowercase()
                if (lower.contains("beta") || lower.contains("aincrad") || lower.contains("anizm")) {
                    jobs.add(Triple(vidId, name, fansubName))
                }
            }
        }

        val streams = jobs.take(8).parallelCatchingFlatMapBlocking { (vidId, name, fansubName) ->
            resolveVideo(origin, vidId, "$name - $fansubName", ajaxHeaders)
        }

        if (streams.isEmpty()) {
            throw Exception("Anizle bu bölüm için oynatılabilir HLS akışı bulamadı")
        }
        return streams
    }

    private fun resolveVideo(
        origin: String,
        videoId: String,
        labelPrefix: String,
        ajaxHeaders: okhttp3.Headers,
    ): List<Video> {
        val videoUrl = "$origin/video/$videoId"
        val videoBody = client.newCall(GET(videoUrl, ajaxHeaders)).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
        val playerHtml = json.parseToJsonElement(videoBody).jsonObject["player"]?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        val pidMatch = Regex("""/player/(\d+)""").find(playerHtml) ?: return emptyList()
        val playerUrl = "$origin/player/${pidMatch.groupValues[1]}"

        val playerHeaders = headersBuilder().set("Referer", "$origin/").build()
        val firstResp = noRedirectClient.newCall(GET(playerUrl, playerHeaders)).execute()
        val (finalPlayerUrl, pageHtml) = firstResp.use { resp ->
            if (resp.isRedirect) {
                val loc = resp.header("Location") ?: return emptyList()
                val target = playerUrl.toHttpUrlOrNull()?.resolve(loc)?.toString() ?: loc
                val isAnizmPlayer = target.contains("puffytr") ||
                    target.contains("anizmplayer") ||
                    mirrors.any { target.startsWith(it) }
                if (!isAnizmPlayer) return emptyList()

                client.newCall(GET(target, playerHeaders)).execute().use { r2 ->
                    r2.request.url.toString() to r2.body.string()
                }
            } else if (resp.isSuccessful) {
                playerUrl to resp.body.string()
            } else {
                return emptyList()
            }
        }

        val masterRe = Regex("""(?:masterUrl|nativeMasterUrl)\s*=\s*"([^"]+)"""")
        val candidates = masterRe.findAll(pageHtml).map { it.groupValues[1] }.toList()
        // Prefer master.txt (/m3/) over native.m3u8 (/mn/) since /mn/ often returns 403
        val ordered = candidates.sortedBy { if (it.contains("master.txt")) 0 else 1 }
        for (path in ordered) {
            val m3u8Url = if (path.startsWith("http")) {
                path
            } else {
                finalPlayerUrl.toHttpUrlOrNull()?.resolve(path)?.toString() ?: continue
            }
            val extracted = extractHlsVariants(m3u8Url, finalPlayerUrl, labelPrefix)
            if (extracted.isNotEmpty()) return extracted
        }

        return emptyList()
    }

    private fun extractHlsVariants(m3u8Url: String, referer: String, labelPrefix: String): List<Video> {
        val streamHeaders = headersBuilder()
            .set("Referer", referer)
            .set("Accept", "*/*")
            .build()
        val body = runCatching {
            client.newCall(GET(m3u8Url, streamHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (!body.trimStart().startsWith("#EXTM3U")) return emptyList()

        val hlsMpvArgs = listOf(
            "demuxer-lavf-o" to "allowed_extensions=ALL,extension_picky=0",
            "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5",
            "demuxer-seekable-cache" to "yes",
        )
        val hlsFfmpegArgs = listOf(
            "allowed_extensions" to "ALL",
            "extension_picky" to "0",
            "f" to "hls",
        )

        val base = m3u8Url.toHttpUrlOrNull()
        val resRe = Regex("""RESOLUTION=\d+x(\d+)""")
        val nameRe = Regex("""NAME="([^"]+)"""")
        val lines = body.lines()
        val variants = mutableListOf<Video>()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val nextUri = lines.getOrNull(i + 1)?.trim() ?: ""
                if (nextUri.isEmpty() || nextUri.startsWith("#")) continue
                val resolved = if (nextUri.startsWith("http")) {
                    nextUri
                } else {
                    base?.resolve(nextUri)?.toString() ?: continue
                }
                val quality = nameRe.find(line)?.groupValues?.get(1)
                    ?: resRe.find(line)?.groupValues?.get(1)?.let { "${it}p" }
                    ?: "HLS"
                variants.add(
                    Video(
                        videoUrl = resolved,
                        videoTitle = "$quality ($labelPrefix)",
                        headers = streamHeaders,
                        mpvArgs = hlsMpvArgs,
                        ffmpegStreamArgs = hlsFfmpegArgs,
                    ),
                )
            }
        }

        if (variants.isEmpty()) {
            variants.add(
                Video(
                    videoUrl = m3u8Url,
                    videoTitle = "Auto ($labelPrefix)",
                    headers = streamHeaders,
                    mpvArgs = hlsMpvArgs,
                    ffmpegStreamArgs = hlsFfmpegArgs,
                ),
            )
        }
        return variants
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
}
