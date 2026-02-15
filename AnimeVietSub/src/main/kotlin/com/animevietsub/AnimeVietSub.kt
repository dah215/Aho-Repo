package com.animevietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Headers giả lập trình duyệt thực để tránh bị chặn
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Trang chủ cơ bản
        val document = app.get(mainUrl, headers = commonHeaders).document
        val homePageList = ArrayList<HomePageList>()
        
        // Logic parse trang chủ (giữ nguyên logic cơ bản thường thấy của site này)
        document.select("div.block_home").forEach { block ->
            val title = block.selectFirst("div.title_home")?.text() ?: "Mới Cập Nhật"
            val list = block.select("ul.list_film li").mapNotNull { element ->
                val name = element.selectFirst("h3.name")?.text() ?: return@mapNotNull null
                val url = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val image = element.selectFirst("img")?.attr("src") ?: ""
                val episode = element.selectFirst("div.episode")?.text()
                
                newAnimeSearchResponse(name, fixUrl(url)) {
                    this.posterUrl = image
                    this.dubStatus = if (episode?.contains("Lồng") == true) DubStatus.Dubbed else DubStatus.Subbed
                    // Thêm tập mới nhất vào info
                    addSub(episode)
                }
            }
            homePageList.add(HomePageList(title, list))
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${query.replace(" ", "-")}/"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("ul.list_film li").mapNotNull { element ->
            val name = element.selectFirst("h3.name")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst("img")?.attr("src") ?: ""
            
            newAnimeSearchResponse(name, fixUrl(href)) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document
        
        val title = doc.selectFirst("h1.name")?.text() ?: "Unknown"
        val description = doc.selectFirst("div.description")?.text()
        val poster = doc.selectFirst("img.poster")?.attr("src")
        val background = doc.selectFirst("div.backdrop")?.attr("style")?.substringAfter("url(")?.substringBefore(")")
        
        // Lấy danh sách tập
        val episodes = ArrayList<Episode>()
        doc.select("div.list_episode a").forEach { ep ->
            val link = ep.attr("href")
            val name = ep.text()
            episodes.add(newEpisode(fixUrl(link)) {
                this.name = name
                this.episode = name.filter { it.isDigit() }.toIntOrNull()
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = description
            this.episodes = episodes
        }
    }

    // --- PHẦN QUAN TRỌNG NHẤT: LOGIC FIX LỖI LINK ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = commonHeaders).document

        // 1. Tìm Episode ID
        // Web thường giấu ID này trong thẻ <input> ẩn hoặc data attribute của player
        val episodeId = doc.selectFirst("input#episode_id")?.attr("value")
            ?: doc.selectFirst("div#player")?.attr("data-id")
            ?: doc.html().substringAfter("episode_id:").substringBefore(",").trim().replace("\"", "")
        
        // Nếu không tìm thấy ID, thử quét luôn HTML trang hiện tại (Fallback)
        if (episodeId.isBlank()) {
            return scanBodyForLinks(doc.html(), data, callback)
        }

        // 2. Gọi API AJAX lấy link (Biện pháp mạnh)
        // Dựa trên log: POST /ajax/player
        val ajaxUrl = "$mainUrl/ajax/player"
        val formData = mapOf(
            "episode_id" to episodeId,
            "backup" to "1" // Thử server chính
        )
        
        val ajaxHeaders = commonHeaders + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to data
        )

        try {
            // Request lên server lấy player
            val response = app.post(ajaxUrl, data = formData, headers = ajaxHeaders).parsedSafe<AjaxResponse>()
            
            if (response?.html != null) {
                val html = response.html
                
                // Cách 1: Quét Regex tìm link .m3u8/.mp4 (Mạnh nhất, bỏ qua parse DOM)
                val foundLinks = scanBodyForLinks(html, data, callback)
                
                // Cách 2: Nếu không tìm thấy link trực tiếp, tìm iframe để giải mã tiếp
                if (!foundLinks) {
                    val soup = Jsoup.parse(html)
                    soup.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotEmpty()) {
                            loadExtractor(src, data, subtitleCallback, callback)
                        }
                    }
                }
            } else {
                // Nếu API fail, fallback về quét trang gốc
                scanBodyForLinks(doc.html(), data, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback cuối cùng
            scanBodyForLinks(doc.html(), data, callback)
        }

        return true
    }

    // Hàm quét Regex "vét cạn" link video
    private fun scanBodyForLinks(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        
        // Regex bắt link m3u8 và mp4, bao gồm cả các link có token dài ngoằng
        val regex = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""")
        
        regex.findAll(html).forEach { match ->
            val link = match.value.replace("\\/", "/") // Fix lỗi escape JSON (https:\/\/...)
            
            val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val nameSource = if (type == ExtractorLinkType.M3U8) "AnimeVietSub (HLS)" else "AnimeVietSub (MP4)"

            callback.invoke(
                ExtractorLink(
                    this.name,
                    nameSource,
                    link,
                    referer,
                    Qualities.Unknown.value,
                    type
                )
            )
            found = true
        }
        return found
    }

    // Class nhận JSON phản hồi
    data class AjaxResponse(
        val status: Int? = null,
        val html: String? = null
    )
}
