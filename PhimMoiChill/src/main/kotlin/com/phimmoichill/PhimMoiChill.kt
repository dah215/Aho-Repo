package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

@CloudstreamPlugin
class PhimMoiChillPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    // Hàm xử lý URL ảnh (Lazy load)
    private fun getImageUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-original", "src")
        for (attr in attrs) {
            val url = el.attr(attr)
            if (!url.isNullOrEmpty() && !url.startsWith("data:image")) {
                return if (url.startsWith("//")) "https:$url" else url
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = defaultHeaders).text
        val items = Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
            
            // FIX LỖI 2: Hiện nhãn Tập/Vietsub trên bìa
            // Lấy text từ .label (vd: Tập 10) hoặc .status (vd: Vietsub)
            val label = el.selectFirst(".label, .status")?.text()?.trim()

            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = getImageUrl(el.selectFirst("img"))
                if (!label.isNullOrEmpty()) {
                    addQuality(label) // Hiển thị thẳng text lấy được lên poster
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}"
        val html = app.get(url, headers = defaultHeaders).text
        return Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
            val label = el.selectFirst(".label, .status")?.text()?.trim()
            
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = getImageUrl(el.selectFirst("img"))
                if (!label.isNullOrEmpty()) {
                    addQuality(label)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        
        // FIX LỖI 1: Poster đen thui -> Dùng getImageUrl thay vì attr("src")
        val poster = getImageUrl(doc.selectFirst(".film-poster img, .movie-l-img img"))
        
        val description = doc.selectFirst(".film-content, .description, #film-content")?.text()?.trim()
        val year = doc.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        
        // Lấy Tags (Thể loại + Quốc gia)
        val tags = doc.select(".entry-meta li a, .tags a").map { it.text() }

        // FIX LỖI 3: Phim lẻ không phát được (không tìm thấy tập)
        // Logic: Tìm link tập, nếu không thấy thì coi như đây là phim lẻ 1 tập
        val episodeList = doc.select("ul.list-episode li a, #list_episodes li a, a.btn-see").mapIndexed { index, it ->
            val link = it.attr("href")
            val name = it.text().trim()
            newEpisode(link) {
                this.name = if (name.isNotEmpty()) name else "Full"
                this.episode = index + 1
            }
        }.distinctBy { it.data }

        // Nếu list rỗng (trường hợp web đổi giao diện hoặc phim lẻ đặc biệt), tự tạo tập mặc định
        val finalEpisodes = if (episodeList.isEmpty()) {
            listOf(newEpisode(url) {
                this.name = "Xem ngay"
                this.episode = 1
            })
        } else {
            episodeList
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        val html = response.text
        
        // Lấy ID tập phim
        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = defaultHeaders.plus(mapOf("Referer" to data, "Origin" to mainUrl)),
                cookies = response.cookies
            ).text

            val key = Regex("""iniPlayers\("([^"]+)""").find(res)?.groupValues?.get(1)
                ?: res.substringAfterLast("iniPlayers(\"").substringBefore("\",")
            
            if (key.length < 5) return false

            val servers = listOf(
                "https://sotrim.topphimmoi.org/manifest/$key/index.m3u8" to "Chill VIP",
                "https://dash.megacdn.xyz/raw/$key/index.m3u8" to "Mega HLS"
            )

            servers.forEach { (link, sName) ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = sName,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // FIX LỖI 4 (3002 Malformed): Thêm Origin vào Header của luồng phát
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
            }
            true
        } catch (e: Exception) { false }
    }
}
