package com.BocTem

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context
import com.lagradost.cloudstream3.APIHolder

@CloudstreamPlugin
class BocTemProvider {
    fun load(context: Context) {
        APIHolder.allProviders.add(BocTem())
    }
}
