package com.BocTem

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BocTemProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(BocTem())
    }
}
