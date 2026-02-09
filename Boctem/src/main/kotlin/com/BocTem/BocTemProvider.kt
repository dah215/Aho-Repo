package com.BocTem

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.APIHolder
import android.content.Context

@CloudstreamPlugin
class BocTemProvider {
    fun load(context: Context) {
        // Directly add to API holder
        APIHolder.allProviders.add(BocTem())
    }
}
