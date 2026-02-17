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

    override val mainPage = mainPageOf(
        "" to "Mới Cập Nhật",
        "the-loai/vietsub/" to "Phim Vietsub",
        "the-loai/jav-hd/" to "JAV HD",
        "the-loai/chau-au/" to "Phim Châu Âu",
        "the-loai/han-quoc/" to "Sex Hàn Quốc"
    )

    private fun fixUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$mainUrl/$url"
    }

    private fun getPoster(el: Element?): String? {
        return el?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: el?.attr("src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?.let { fixUrl(it) }
    }

    private fun parseItem(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").ifEmpty { el.selectFirst("h3")?.text() ?: return null }
        val poster = getPoster(el.selectFirst("img"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
        val doc = app.get(url).document
        val items = doc.select("article.item").mapNotNull { parseItem(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("article.item").mapNotNull { parseItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: getPoster(doc.selectFirst(".thumb img"))
        val desc = doc.selectFirst(".entry-content p, .single-content p")?.text()?.trim()
        val tags = doc.select("a[rel=tag], .genres a").map { it.text().trim() }

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
        val doc = app.get(data).document
        doc.select("iframe[src], iframe[data-src]").forEach {
            val src = it.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: it.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
