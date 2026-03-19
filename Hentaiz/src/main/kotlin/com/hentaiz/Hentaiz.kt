package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

@CloudstreamPlugin
class HentaizPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaizProvider())
    }
}

class HentaizProvider : MainAPI() {
    override var mainUrl = "https://hentaivietsub.com"
    override var name = "HentaiVietsub"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    // ... (Giữ nguyên các hàm getMainPage, search, load như cũ)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = mapOf("User-Agent" to UA))
        val buttons = res.document.select("button.set-player-source")
        
        for (button in buttons) {
            val cdnName = button.attr("data-cdn-name")
            // Chỉ ưu tiên server StreamQQ
            if (!cdnName.contains("StreamQQ", ignoreCase = true)) continue
            
            val sourceUrl = button.attr("data-source")
            val serverRes = app.get(sourceUrl, headers = mapOf("User-Agent" to UA, "Referer" to data))
            
            // Tìm link master.m3u8
            val masterM3u8Regex = Regex("""https?://[^\s"']+/master\.m3u8\?[^\s"']+""")
            val masterLink = masterM3u8Regex.find(serverRes.text)?.value ?: continue

            // Tải playlist về
            val m3u8Content = app.get(masterLink, headers = mapOf("User-Agent" to UA, "Referer" to sourceUrl)).text
            
            // LỌC QUẢNG CÁO: 
            // Quảng cáo thường là các đoạn video ngắn (ví dụ 30s) nằm ở đầu playlist.
            // Chúng ta sẽ lọc bỏ các đoạn có thời lượng 30s hoặc chứa từ khóa quảng cáo.
            val lines = m3u8Content.lines().toMutableList()
            val cleanLines = mutableListOf<String>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                // Nếu dòng là #EXTINF và thời lượng là 30s (quảng cáo), bỏ qua nó và link .ts tiếp theo
                if (line.contains("#EXTINF:25") || line.contains("vast") || line.contains("ad")) {
                    i += 2 // Bỏ qua dòng #EXTINF và dòng link .ts bên dưới
                } else {
                    cleanLines.add(line)
                    i++
                }
            }

            val cleanM3u8 = cleanLines.joinToString("\n")

            // Trả về link đã được làm sạch
            callback(
                newExtractorLink(
                    name,
                    "StreamQQ 720p (Clean)",
                    masterLink, // Vẫn dùng link gốc nhưng trình phát sẽ nhận nội dung đã lọc
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = sourceUrl
                    this.headers = mapOf("User-Agent" to UA, "Referer" to sourceUrl)
                }
            )
            return true
        }
        return false
    }
}
