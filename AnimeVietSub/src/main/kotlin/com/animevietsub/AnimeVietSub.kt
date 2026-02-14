package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name   = "AnimeVietSub"
    override var lang   = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val DECODE_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"
    }

    private val cfKiller = CloudflareKiller()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent"      to ua,
        "Referer"         to "$mainUrl/",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DECRYPT: AES-256-CBC + pako.inflateRaw
    // Key: SHA-256(password)
    // IV: first 16 bytes của decoded data
    // Base64: DEFAULT (standard +/=), KHÔNG phải URL_SAFE
    // ─────────────────────────────────────────────────────────────────────────
    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            if (decoded.size < 17) return null
            val iv     = decoded.copyOfRange(0, 16)
            val ct     = decoded.copyOfRange(16, decoded.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain  = cipher.doFinal(ct)
            String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
                .replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) { null }
    }

    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        } finally { inflater.end() }
        return out.toByteArray()
    }

    // ── Search / MainPage ─────────────────────────────────────────────────────
    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href   = selectFirst("a")?.attr("href")            ?: return null
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
        }
        val epText = selectFirst(".mli-eps i, .mli-eps, .Epnum, .ep-count")?.text()?.trim() ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            Regex("\\d+").find(epText)?.value?.toIntOrNull()?.let { addSub(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val items = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    // Episode data: "epUrl@@filmId@@episodeId"
    //   epUrl:     trang xem tập (vd: /tap-01-111047.html) — dùng làm Referer
    //   filmId:    từ URL phim (a5820 → "5820") — dùng trong POST bước 2
    //   episodeId: data-id của tập (111047) — dùng trong POST bước 1
    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        if (episodesNodes.isEmpty()) {
            document.selectFirst("a[href*='/tap-']")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                ?.let { firstEp ->
                    runCatching {
                        app.get(firstEp, interceptor = cfKiller, headers = defaultHeaders).document
                    }.getOrNull()?.also { doc2 ->
                        episodesNodes = doc2.select("ul.list-episode li a[data-id]")
                        if (episodesNodes.isNotEmpty()) document = doc2
                    }
                }
        }

        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1) ?: ""

        val epList = episodesNodes.mapNotNull { ep ->
            val episodeId = ep.attr("data-id").trim()
            val epName    = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl     = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (episodeId.isEmpty() || epUrl.isEmpty()) return@mapNotNull null
            newEpisode("$epUrl@@$filmId@@$episodeId") {
                name    = epName
                episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title, .TPost h1")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = document.selectFirst(".Image img, .InfoImg img")
                ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description")?.text()?.trim()
            episodes = if (epList.isNotEmpty()) mutableMapOf(DubStatus.Subbed to epList) else mutableMapOf()
        }
    }

    // ── loadLinks ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) return false
        val epUrl     = parts[0]  // URL trang tập
        val filmId    = parts[1]  // Film ID (5820)
        val episodeId = parts[2]  // Episode ID (111047)

        // Warm CF cookies
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Origin"           to mainUrl,
            "User-Agent"       to ua
        )

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 1: Lấy FRESH hash từ server
        //
        // v14 BUG: lấy hash từ HTML trang tập → STALE, server reject
        // FIX: gọi /ajax/player với episodeId → nhận hash MỚI trong response
        //
        // Xác nhận từ Player2.txt:
        //   POST /ajax/player   body: episodeId=111047&backup=1
        //   Response: {"html":"<a data-href='FRESH_HASH' data-play='api'>DU</a>"}
        // ═══════════════════════════════════════════════════════════════════════
        val step1 = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = mapOf("episodeId" to episodeId, "backup" to "1"),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            ).parsedSafe<ServerSelectionResp>()
        }.getOrNull()

        val serverHtml = step1?.html ?: return false
        val serverDoc  = Jsoup.parse(serverHtml)

        // Ưu tiên server "api" (DU) — không có quảng cáo
        val prefServer = serverDoc.selectFirst("a.btn3dsv[data-play=api]")
            ?: serverDoc.selectFirst("a.btn3dsv")
            ?: return false

        val freshHash  = prefServer.attr("data-href").trim().ifEmpty { return false }
        val serverPlay = prefServer.attr("data-play").trim().ifEmpty { "api" }
        val serverBtnId = prefServer.attr("data-id").trim() // data-id của SERVER BUTTON

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 2: Lấy encrypted link dùng FRESH hash
        //
        // Xác nhận từ Player1.txt:
        //   POST /ajax/player   body: link=FRESH_HASH&id=5820  (id = FILM ID)
        //   Response: {"_fxStatus":1,"link":[{"file":"Q/x+SJ6..."}],"playTech":"api"}
        //
        // Xác nhận từ Player3.txt (HDX embed):
        //   POST /ajax/player   body: link=hash&play=embed&id=3&backuplinks=1  (id = SERVER BTN ID)
        //   Response: {"_fxStatus":1,"link":"https://short.icu/...","playTech":"embed"}
        // ═══════════════════════════════════════════════════════════════════════
        val step2Params = when (serverPlay) {
            "api"  -> mapOf("link" to freshHash, "id" to filmId)
            else   -> mapOf("link" to freshHash, "play" to serverPlay, "id" to serverBtnId, "backuplinks" to "1")
        }

        val step2Resp = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = step2Params,
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            )
        }.getOrNull() ?: return false

        val body = step2Resp.text.trim()
        if (body.isBlank() || body.startsWith("<")) return false

        val parsed = step2Resp.parsedSafe<PlayerResp>() ?: return false
        if (parsed.fxStatus != 1 && parsed.success != 1) return false

        return when (serverPlay) {
            // ── DU server: link là [{file: "<standard_base64_encrypted>"}]
            "api" -> {
                val enc = parsed.linkArray?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                    ?: return false
                // Decrypt: Base64.DEFAULT (standard, có +/=) → AES → inflate → URL
                val dec = decryptLink(enc) ?: return false
                handleDecrypted(dec, epUrl, callback, subtitleCallback)
            }
            // ── HDX server: link là URL trực tiếp (short link, có quảng cáo)
            else -> {
                val direct = parsed.linkString?.takeIf { it.startsWith("http") } ?: return false
                loadExtractor(direct, epUrl, subtitleCallback, callback)
                true
            }
        }
    }

    private suspend fun handleDecrypted(
        dec: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = dec.trim()
        return when {
            link.startsWith("http") && link.contains(".m3u8") -> {
                M3u8Helper.generateM3u8(name, link, referer).forEach(callback); true
            }
            link.startsWith("#EXTM3U") -> {
                link.lines().forEachIndexed { i, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                        val q  = when {
                            bw == null      -> Qualities.Unknown.value
                            bw >= 4_000_000 -> Qualities.P1080.value
                            bw >= 2_000_000 -> Qualities.P720.value
                            bw >= 1_000_000 -> Qualities.P480.value
                            else            -> Qualities.P360.value
                        }
                        link.lines().getOrNull(i + 1)?.trim()?.takeIf { it.startsWith("http") }?.let { u ->
                            callback(newExtractorLink(name, name, u) {
                                this.referer = referer; this.quality = q; this.type = ExtractorLinkType.M3U8
                            })
                        }
                    }
                }
                true
            }
            link.startsWith("http") -> { loadExtractor(link, referer, subtitleCallback, callback); true }
            link.contains("\n") -> {
                link.lines().filter { it.startsWith("http") }.forEach { u ->
                    if (u.contains(".m3u8")) M3u8Helper.generateM3u8(name, u, referer).forEach(callback)
                    else loadExtractor(u, referer, subtitleCallback, callback)
                }
                true
            }
            else -> false
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class ServerSelectionResp(
        @JsonProperty("success") val success: Int?  = null,
        @JsonProperty("html")    val html: String?  = null
    )

    data class PlayerResp(
        @JsonProperty("_fxStatus") val fxStatus: Int?    = null,
        @JsonProperty("success")   val success: Int?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        @JsonProperty("link") private val linkRaw: Any?  = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkFile>?
            get() = (linkRaw as? List<*>)
                ?.filterIsInstance<Map<String, Any?>>()
                ?.map { LinkFile(it["file"] as? String) }

        val linkString: String?
            get() = linkRaw as? String
    }

    data class LinkFile(val file: String? = null)
}
