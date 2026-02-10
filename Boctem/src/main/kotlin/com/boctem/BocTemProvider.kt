package com.boctem

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class BocTemProvider: CloudstreamPlugin() {
    override fun load(context: Context) {
        // Tất cả các provider phải được đăng ký ở đây
        registerMainAPI(BocTem())
    }
}
