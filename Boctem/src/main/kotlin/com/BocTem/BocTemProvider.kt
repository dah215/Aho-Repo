package com.BocTem

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.MainAPI

@CloudstreamPlugin
class BocTemProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(BocTem())
    }
}
