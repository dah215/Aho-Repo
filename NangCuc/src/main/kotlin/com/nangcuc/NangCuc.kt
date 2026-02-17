package com.nangcuc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class NangCucPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NangCucProvider())
    }
}

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cv"
    override var name = "Nắng Cực"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "" to "Mới Cập Nhật",
        "the-loai/vietsub/" to "Vietsub",
        "the-loai/jav-hd/" to "JAV HD",
        "the-loai/chau-au/" to "Âu Mỹ",
        "the-loai/han-quoc-18/" to "Hàn Quốc 18+",
        "the-loai/xvideos/" to "Xvideos"
    )

    private fun fixUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$mainUrl/$url"
    }

    private fun getPoster(el: Element?): String? {
        return el?.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotEmpty() && !it.contains("blank") }
    }

    private fun parseItem(el: Element): SearchResponse? {
        val linkEl = el.selectFirst("a.item-box__image") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val title = linkEl.attr("title").ifBlank { el.selectFirst("p.item-box__title")?.text() ?: return null }
        val poster = getPoster(el)
        return newMovieSearchResponse(title.trim(), href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.item-box").mapNotNull { parseItem(it) }
        val hasNext = doc.selectFirst("a.pagination-next, link[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=movie"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.item-box").mapNotNull { parseItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: getPoster(doc.selectFirst("div.thumb, div.entry-thumb"))
        val desc = doc.selectFirst(".entry-content, .single-content, .description")?.text()?.trim()
        val tags = doc.select("a[rel=tag], .genres a, .tags a").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document

        // Xử lý iframe
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[srcdoc]").forEach {
            val src = it.attr("data-lazy-src").takeIf { s -> s.isNotEmpty() }
                ?: it.attr("data-src").takeIf { s -> s.isNotEmpty() }
                ?: it.attr("src").takeIf { s -> s.isNotEmpty() }
                ?: return@forEach
            val iframeUrl = fixUrl(src)
            if (iframeUrl.contains("dfplayer.net")) {
                // Đặc biệt cho dfplayer: fetch iframe với referer đúng
                val iframeDoc = app.get(iframeUrl, headers = headers, referer = data).document
                iframeDoc.select("script").forEach { script ->
                    val content = script.html()
                    val m3u8Regex = Regex("""["']?file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                    m3u8Regex.find(content)?.groupValues?.get(1)?.let { m3u8 ->
                        val link = fixUrl(m3u8)
                        callback(
                            newExtractorLink(
                                name = name,
                                url = link,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                }
            } else if (iframeUrl.isNotEmpty()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // Parse m3u8 trực tiếp từ script trên trang chính (Playerjs)
        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.contains(".m3u8")) {
                val m3u8Regex = Regex("""["']?file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                m3u8Regex.findAll(content).forEach { match ->
                    val link = fixUrl(match.groupValues[1])
                    callback(
                        newExtractorLink(
                            name = name,
                            url = link,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        }

        return true
    }
}
