package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override val lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val AJAX_URL = "/ajax/all"
        private const val AES_KEY = "anhemlun@animevs"
        private const val AES_IV = "@animevsub@anime"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang chủ",
        "$mainUrl/anime-bo/" to "Anime Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-sap-chieu/" to "Sắp Chiếu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("div.TPost.B, article.TPost").mapNotNull { item ->
            item.toSearchResponse()
        }
        return HomePageResponse(
            list = listOf(HomePageList(request.name, items, true)),
            hasNext = false
        )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("div.Title, h3.Title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val epInfo = this.selectFirst("span.mli-eps")?.text()
        val quality = this.selectFirst("span.mli-quality")?.text()
        val type = if (href.contains("/anime-le/")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            posterUrl?.let { this.posterUrl = fixUrl(it) }
            epInfo?.let { this.subEpText = it }
            quality?.let { this.quality = getQualityFromString(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/$query/").document
        return document.select("div.TPost.B, article.TPost").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: ""
        val altTitle = document.selectFirst("h2.SubTitle")?.text()?.trim()
        val poster = document.selectFirst("div.Image figure.Objf img")?.attr("src")
        val banner = document.selectFirst("div.TPostBg img")?.attr("src")
        val description = document.selectFirst("div.Description")?.text()?.trim()
        val year = document.selectFirst("span.Date a")?.text()?.toIntOrNull()
        val rating = document.selectFirst("input#score_current")?.attr("value")?.toFloatOrNull()

        val tags = document.select("ul.InfoList li:has(strong:contains(Thể loại)) a").map {
            it.text().trim()
        }

        val statusText = document.selectFirst("ul.InfoList li:has(strong:contains(Trạng thái))")?.text()
        val status = when {
            statusText?.contains("Hoàn Tất", true) == true -> ShowStatus.Completed
            statusText?.contains("Đang chiếu", true) == true -> ShowStatus.Ongoing
            else -> null
        }

        val filmIdMatch = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"](\d+)['"]\)""").find(document.html())
        val filmId = filmIdMatch?.groupValues?.get(1)

        val episodes = mutableListOf<Episode>()
        document.select("ul.list-episode li.episode a, div.server ul li a").forEach { epElement ->
            val epTitle = epElement.attr("title") ?: epElement.text()
            val epHref = epElement.attr("href")
            val epId = epElement.attr("data-id")
            val epHash = epElement.attr("data-hash")
            val epSource = epElement.attr("data-source") ?: "du"
            val epPlay = epElement.attr("data-play") ?: "api"

            val epNum = Regex("""Tập\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(Episode(
                data = if (epHash.isNotEmpty()) "$epHref|$epHash|$epId|$epSource|$epPlay" else epHref,
                name = epTitle,
                episode = epNum
            ))
        }

        val trailerUrl = document.selectFirst("div#MvTb-Trailer iframe")?.attr("src")
        val recommendations = document.select("div.MovieListTop div.TPost.B").mapNotNull {
            it.toSearchResponse()
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            japName = altTitle
            posterUrl = poster?.let { fixUrl(it) }
            backgroundPosterUrl = banner?.let { fixUrl(it) }
            this.year = year
            plot = description
            tags = tags
            this.rating = rating
            this.status = status
            episodes = if (episodes.isEmpty()) listOf(Episode(url)) else episodes
            trailerUrl?.let { addTrailer(it) }
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val url = parts[0]
        val hash = parts.getOrNull(1)
        val episodeId = parts.getOrNull(2)
        val source = parts.getOrNull(3) ?: "du"
        val playType = parts.getOrNull(4) ?: "api"

        return when (playType) {
            "api" -> loadApiLinks(hash ?: "", episodeId ?: "", source, callback)
            "embed" -> loadEmbedLinks(url, callback)
            else -> loadEmbedLinks(url, callback)
        }
    }

    private suspend fun loadApiLinks(
        hash: String,
        episodeId: String,
        source: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (hash.isEmpty()) return false

        try {
            val decryptedUrl = decryptHash(hash)
            if (decryptedUrl.isNullOrEmpty()) return false

            if (decryptedUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = "$name - $source",
                    streamUrl = decryptedUrl,
                    referer = mainUrl
                ).forEach(callback)
                return true
            }

            if (decryptedUrl.contains(".mp4")) {
                callback(
                    ExtractorLink(
                        source = "$name - $source",
                        name = "$name - $source",
                        url = decryptedUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                return true
            }

            loadExtractor(decryptedUrl, mainUrl, subtitleCallback, callback)
            return true
        } catch (e: Exception) {
            return loadAjaxLinks(episodeId, source, callback)
        }
    }

    private suspend fun loadAjaxLinks(
        episodeId: String,
        source: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (episodeId.isEmpty()) return false

        try {
            val ajaxData = mapOf(
                "action" to "get_episodes_player",
                "episode_id" to episodeId,
                "server" to source
            )

            val response = app.post(
                "$mainUrl$AJAX_URL",
                data = ajaxData,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).parsedSafe<AjaxResponse>()

            response?.data?.let { data ->
                if (data.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = data,
                        referer = mainUrl
                    ).forEach(callback)
                    return true
                }

                if (data.contains(".mp4") || data.contains("http")) {
                    loadExtractor(data, mainUrl, subtitleCallback = {}, callback)
                    return true
                }
            }
        } catch (e: Exception) {
        }
        return false
    }

    private suspend fun loadEmbedLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(url, referer = mainUrl).document

            document.select("iframe").forEach { iframe ->
                val embedUrl = iframe.attr("src")
                if (embedUrl.isNotEmpty()) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback = {}, callback)
                }
            }

            document.select("script").forEach { script ->
                val content = script.html()
                Regex("""['"]((https?:)?//[^\s'"]+\.(?:m3u8|mp4)[^\s'"]*)['"]""")
                    .findAll(content)
                    .forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = videoUrl,
                                referer = mainUrl
                            ).forEach(callback)
                        } else {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = name,
                                    url = videoUrl,
                                    referer = mainUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = false
                                )
                            )
                        }
                    }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun decryptHash(hash: String): String? {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decoded = Base64.decode(hash, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            decryptAlternative(hash)
        }
    }

    private fun decryptAlternative(hash: String): String? {
        return try {
            val decoded = Base64.decode(hash, Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    data class AjaxResponse(
        @JsonProperty("status") val status: Boolean?,
        @JsonProperty("data") val data: String?,
        @JsonProperty("message") val message: String?
    )
}
