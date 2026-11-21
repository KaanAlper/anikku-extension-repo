package eu.kanade.tachiyomi.animeextension.tr.animecix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

@Source
abstract class AnimeciX : AnimeHttpLegacySource() {

    override val supportsLatest = true

    private val altUrl = "https://mangacix.net"
    private val tauHost = "tau-video.xyz"
    private val json = Json { ignoreUnknownKeys = true }

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("x-e-h", "=.a")
            .build()
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/secure/titles?type=series&order=popularity:desc&page=$page"
        val body = client.newCall(GET(url, apiHeaders)).awaitSuccess().use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val pagination = root["pagination"]?.jsonObject
        val data = pagination?.get("data")?.jsonArray ?: JsonArray(emptyList())
        val animes = data.mapNotNull { parseSearchItem(it.jsonObject) }
        val hasNext = pagination?.get("next_page_url")?.jsonPrimitive?.contentOrNull != null
        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "$baseUrl/secure/titles?type=series&order=updated_at:desc&page=$page"
        val body = client.newCall(GET(url, apiHeaders)).awaitSuccess().use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val pagination = root["pagination"]?.jsonObject
        val data = pagination?.get("data")?.jsonArray ?: JsonArray(emptyList())
        val animes = data.mapNotNull { parseSearchItem(it.jsonObject) }
        val hasNext = pagination?.get("next_page_url")?.jsonPrimitive?.contentOrNull != null
        return AnimesPage(animes, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val q = slugifyTr(query)
        val url = "$baseUrl/secure/search/$q?type=&limit=20"
        val body = client.newCall(GET(url, apiHeaders)).awaitSuccess().use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
        val animes = results.mapNotNull { parseSearchItem(it.jsonObject) }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun parseSearchItem(item: JsonObject): SAnime? {
        val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val titleType = item["title_type"]?.jsonPrimitive?.contentOrNull ?: ""
        val poster = item["poster"]?.jsonPrimitive?.contentOrNull
        return SAnime.create().apply {
            url = "$id|$titleType"
            title = name
            thumbnail_url = poster
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url.substringBefore("|")
        val url = "$baseUrl/secure/titles/$animeId?titleId=$animeId"
        val body = client.newCall(GET(url, apiHeaders)).awaitSuccess().use { it.body.string() }
        val titleObj = json.parseToJsonElement(body).jsonObject["title"]?.jsonObject ?: return anime
        return SAnime.create().apply {
            this.url = anime.url
            title = titleObj["name"]?.jsonPrimitive?.contentOrNull ?: anime.title
            thumbnail_url = titleObj["poster"]?.jsonPrimitive?.contentOrNull ?: anime.thumbnail_url
            description = titleObj["description"]?.jsonPrimitive?.contentOrNull
            genre = titleObj["genres"]?.jsonArray
                ?.mapNotNull { it.jsonObject["display_name"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ")
            initialized = true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.split("|")
        val animeId = parts[0]
        val isMovie = parts.getOrNull(1)?.equals("movie", ignoreCase = true) == true

        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    url = "movie:$animeId"
                    name = "${anime.title} (Film)"
                    episode_number = 1f
                },
            )
        }

        val firstUrl = "$altUrl/secure/related-videos?episode=1&season=1&titleId=$animeId&videoId=637113"
        val firstBody = client.newCall(GET(firstUrl, apiHeaders)).awaitSuccess().use { it.body.string() }
        val firstData = json.parseToJsonElement(firstBody).jsonObject

        val seasonCount = firstData["videos"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("title")?.jsonObject
            ?.get("seasons")?.jsonArray
            ?.size?.coerceAtLeast(1) ?: 1

        val episodes = mutableListOf<SEpisode>()
        val seen = HashSet<String>()

        for (s in 1..seasonCount) {
            val data = if (s == 1) {
                firstData
            } else {
                val u = "$altUrl/secure/related-videos?episode=1&season=$s&titleId=$animeId&videoId=637113"
                val b = client.newCall(GET(u, apiHeaders)).awaitSuccess().use { it.body.string() }
                json.parseToJsonElement(b).jsonObject
            }

            val videos = data["videos"]?.jsonArray ?: continue
            for (v in videos) {
                val obj = v.jsonObject
                val epName = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Bölüm"
                val epUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                val seasonNum = obj["season_num"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: s
                if (epUrl.isNotEmpty() && seen.add(epName)) {
                    val epNum = (episodes.size + 1).toFloat()
                    episodes.add(
                        SEpisode.create().apply {
                            url = "$animeId|$seasonNum|$epUrl"
                            name = epName
                            episode_number = epNum
                        },
                    )
                }
            }
        }

        // Fallback for OVAs/Movies tagged with title_type="anime"
        if (episodes.isEmpty()) {
            val titleUrl = "$baseUrl/secure/titles/$animeId?titleId=$animeId"
            val titleBody = runCatching {
                client.newCall(GET(titleUrl, apiHeaders)).awaitSuccess().use { it.body.string() }
            }.getOrNull()
            if (titleBody != null) {
                val videos = json.parseToJsonElement(titleBody).jsonObject["title"]
                    ?.jsonObject?.get("videos")?.jsonArray
                if (!videos.isNullOrEmpty()) {
                    episodes.add(
                        SEpisode.create().apply {
                            url = "movie:$animeId"
                            name = "${anime.title} (Film/OVA)"
                            episode_number = 1f
                        },
                    )
                }
            }
        }

        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (episode.url.startsWith("movie:")) {
            val animeId = episode.url.removePrefix("movie:")
            val titleUrl = "$baseUrl/secure/titles/$animeId?titleId=$animeId"
            val body = client.newCall(GET(titleUrl, apiHeaders)).awaitSuccess().use { it.body.string() }
            val videos = json.parseToJsonElement(body).jsonObject["title"]
                ?.jsonObject?.get("videos")?.jsonArray
                ?: throw Exception("Film video listesi bulunamadı")

            for (v in videos) {
                val obj = v.jsonObject
                val videoUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                val tracks = extractCaptionTracks(obj["captions"]?.jsonArray)
                val streams = runCatching { extractTauStreams(videoUrl, tracks) }.getOrNull()
                if (!streams.isNullOrEmpty()) {
                    return streams
                }
            }
            throw Exception("Film için çalışan video bağlantısı bulunamadı")
        }

        val parts = episode.url.split("|", limit = 3)
        val animeId = parts[0]
        val seasonNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val epUrl = parts.getOrNull(2) ?: episode.url

        val target = if (epUrl.startsWith("http")) {
            epUrl
        } else {
            "$baseUrl/${epUrl.trimStart('/')}"
        }

        val tracks = fetchTrCaption(animeId, seasonNum, epUrl)
        return extractTauStreams(target, tracks)
    }

    private suspend fun extractTauStreams(redirectTarget: String, subtitleTracks: List<Track>): List<Video> {
        val resp = client.newCall(GET(redirectTarget, apiHeaders)).awaitSuccess()
        val finalUrl = resp.use {
            if (it.code == 422) throw Exception("Bölüm verisi beklenen formatta değil (422)")
            it.request.url
        }

        val segments = finalUrl.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2) {
            throw Exception("Beklenmeyen embed URL formatı: $finalUrl")
        }
        val embedId = segments[1]
        val vid = finalUrl.queryParameter("vid") ?: ""

        val apiUrl = "https://$tauHost/api/video/$embedId?vid=$vid"
        val videoBody = client.newCall(GET(apiUrl, headers)).awaitSuccess().use { it.body.string() }
        val videoJson = json.parseToJsonElement(videoBody).jsonObject
        val urls = videoJson["urls"]?.jsonArray ?: JsonArray(emptyList())

        val streamHeaders = headersBuilder()
            .set("Referer", "https://yeshi.eu.org/")
            .build()

        val videos = urls.mapNotNull { item ->
            val obj = item.jsonObject
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "Varsayılan"
            val u = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Video(
                videoUrl = u,
                videoTitle = label,
                headers = streamHeaders,
                subtitleTracks = subtitleTracks,
            )
        }

        if (videos.isEmpty()) {
            throw Exception("Bu bölüm için oynatılabilir video akışı bulunamadı")
        }
        return videos
    }

    private suspend fun fetchTrCaption(titleId: String, seasonNum: Int, epUrl: String): List<Track> {
        val url = "$altUrl/secure/related-videos?episode=1&season=$seasonNum&titleId=$titleId&videoId=637113"
        val body = runCatching {
            client.newCall(GET(url, apiHeaders)).awaitSuccess().use { it.body.string() }
        }.getOrNull() ?: return emptyList()

        val videos = json.parseToJsonElement(body).jsonObject["videos"]?.jsonArray ?: return emptyList()
        val target = videos.firstOrNull {
            it.jsonObject["url"]?.jsonPrimitive?.contentOrNull == epUrl
        } ?: videos.firstOrNull() ?: return emptyList()

        return extractCaptionTracks(target.jsonObject["captions"]?.jsonArray)
    }

    private fun extractCaptionTracks(captions: JsonArray?): List<Track> {
        if (captions == null || captions.isEmpty()) return emptyList()
        val tracks = mutableListOf<Track>()
        for (cap in captions) {
            val obj = cap.jsonObject
            val lang = obj["language"]?.jsonPrimitive?.contentOrNull ?: "tr"
            val capUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val validUrl = capUrl.toHttpUrlOrNull()?.toString() ?: continue
            if (lang == "tr") {
                tracks.add(0, Track(validUrl, "Turkish"))
            } else {
                tracks.add(Track(validUrl, lang))
            }
        }
        return tracks
    }

    private fun slugifyTr(input: String): String {
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
        return out.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("-")
    }
}
