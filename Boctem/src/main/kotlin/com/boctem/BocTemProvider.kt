package com.boctem // Sửa thành chữ thường

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // Thêm import này
import android.content.Context
import com.lagradost.cloudstream3.APIHolder

@CloudstreamPlugin
class BocTemProvider : Plugin() { // Kế thừa Plugin
    override fun load(context: Context) {
        // Tất cả các provider phải được đăng ký ở đây
        APIHolder.allProviders.add(BocTem())
    }
}
