package com.watchreader.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 兼容入口：旧代码/外部显式启动 PhoneFanqieActivity 时，重定向到持有 FanqieController 的主壳。
 * 页面状态由 PhoneMainActivity 保留，不再单独创建番茄 Activity。
 */
class PhoneFanqieActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, PhoneMainActivity::class.java)
                .putExtra(ExtraRootTab, RootTabFanqieValue)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
