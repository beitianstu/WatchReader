package com.watchreader.fanqie

import com.watchreader.phone.ReadingAppTheme
import com.watchreader.phone.ReadingAppearance
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiTextField
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiCompactTitleScaffold
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.utils.mochiEnableEdgeToEdge

/**
 * 插件账号设置（手机 Mochi 版）：保留原来的调用方校验、FLAG_SECURE、SAF 导入、
 * 粘贴加密保存与清除语义；Cookie 仍只由 CredentialStore 加密写入插件私有存储。
 *
 * 组件名仍是 `com.watchreader.fanqie.PluginSetupActivity`（Manifest activity-alias），
 * 所以 RemotePluginClient 的显式 Intent 不需要改；旧 Java 同名类保留但不再注册。
 */
class MochiPluginSetupActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trusted = false
    private var busy by mutableStateOf(false)
    private var loggedIn by mutableStateOf(false)
    private var pasting by mutableStateOf(false)
    private var draft by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@registerForActivityResult
        // busy 由这里统一持有：读文件失败和保存失败都要复位，不能让页面卡在「正在处理…」
        busy = true
        message = null
        scope.launch {
            var reading = true
            try {
                val value = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IOException("无法读取账号文件")
                    String(PluginWire.readLimited(stream, 512 * 1024), Charsets.UTF_8)
                }
                reading = false
                persist(value)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                message = if (reading) "无法读取账号文件" else "账号保存失败，请检查 Cookie 格式或有效期"
            } finally {
                busy = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mochiEnableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        trusted = PluginTrust.HOST == callingPackage && PluginTrust.host(this)
        if (!trusted) {
            setContent { PluginTheme { UntrustedPage() } }
            return
        }
        if (intent.getBooleanExtra("connect_only", false)) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        loggedIn = getSharedPreferences("vault", Context.MODE_PRIVATE).contains("cookie")
        setContent { PluginTheme { SetupPage() } }
    }

    @Composable
    private fun PluginTheme(content: @Composable () -> Unit) {
        ReadingAppTheme(if (trusted) intent.getStringExtra(ReadingAppearance.KEY_THEME) else ReadingAppearance.DEFAULT_THEME) {
            ProvideMochiOverscroll { content() }
        }
    }

    @Composable
    private fun UntrustedPage() {
        MochiCompactTitleScaffold(
            title = "番茄数据源插件",
            subtitle = "仅供配套 WatchReader 使用",
        ) {
            MochiListSection(header = "无法打开", footer = "请从配套 WatchReader 主程序的「番茄插件」入口进入。") {
                MochiListRow("调用方未通过校验", subtitle = "主程序包名与证书必须同时匹配", destructive = true)
            }
            MochiListSection {
                MochiListButton("关闭", onClick = { finish() })
            }
        }
    }

    @Composable
    private fun SetupPage() {
        MochiCompactTitleScaffold(
            title = "番茄插件",
            subtitle = if (loggedIn) "已连接 · 账号仅保存在插件内" else "未导入账号",
            actions = {
                MochiBarButton("完成", onClick = { if (!busy) finishWithResult() }, navId = "plugin.done")
            },
        ) {
            if (pasting) {
                MochiListSection(
                    header = "粘贴 Cookie / JSON",
                    footer = "保存时立即加密；明文不会落盘，也不会传给主程序。",
                ) {
                    MochiTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "sessionid=…",
                        secure = true,
                        singleLine = false,
                        maxLines = 6,
                    )
                }
                message?.let { text ->
                    MochiListSection(header = "提示", footer = text) {
                        MochiListRow("未保存", subtitle = text, destructive = true)
                    }
                }
                MochiButton(
                    onClick = { save(draft) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("加密保存", style = MochiTheme.textStyles.bodyLarge)
                }
                MochiListSection {
                    MochiListButton("取消", onClick = {
                        if (!busy) {
                            draft = ""
                            pasting = false
                        }
                    })
                }
            } else {
                MochiListSection(
                    header = "账号",
                    footer = "账号仅在插件内用 AndroidKeyStore 不可导出密钥加密保存；主程序只读取书架与进度。",
                ) {
                    MochiListRow("连接状态", value = if (loggedIn) "已连接" else "未连接")
                    MochiListRow(
                        title = "选择 Cookie 文件",
                        subtitle = "支持导出的 Cookie 文本或 JSON",
                        chevron = true,
                        enabled = !busy,
                        onClick = { if (!busy) filePicker.launch(arrayOf("*/*")) },
                    )
                    MochiListRow(
                        title = "粘贴 Cookie",
                        subtitle = "手动粘贴 sessionid 等字段",
                        chevron = true,
                        enabled = !busy,
                        onClick = {
                            if (!busy) {
                                draft = ""
                                message = null
                                pasting = true
                            }
                        },
                    )
                }
                message?.let { text ->
                    MochiListSection(header = "提示", footer = text) {
                        MochiListRow("账号保存失败", subtitle = text, destructive = true)
                    }
                }
                MochiListSection(
                    header = "退出",
                    footer = "清除后主程序会回到未连接状态，需要重新导入账号。",
                ) {
                    MochiListRow(
                        title = "退出并清除账号",
                        subtitle = "删除插件内保存的 Cookie",
                        destructive = true,
                        enabled = !busy,
                        onClick = { if (!busy) clearAccount() },
                    )
                }
                if (busy) {
                    MochiListSection {
                        MochiListRow("正在处理…", loading = true)
                    }
                }
            }
        }
    }

    /** 唯一的写入路径：调用方负责 busy 状态与错误提示，这里不判断 busy。 */
    private suspend fun persist(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            message = "账号内容为空，请检查文件或重新粘贴"
            return
        }
        withContext(Dispatchers.IO) {
            FanqiePlugin(CredentialStore(this@MochiPluginSetupActivity)).setCookie(trimmed)
        }
        loggedIn = true
        draft = ""
        finishWithResult()
    }

    /** 粘贴框的「加密保存」：自己持有 busy，避免和文件导入互相抢状态。 */
    private fun save(value: String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                persist(value)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                message = "账号保存失败，请检查 Cookie 格式或有效期"
            } finally {
                busy = false
            }
        }
    }

    private fun clearAccount() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CredentialStore(this@MochiPluginSetupActivity).clear()
                }
                loggedIn = false
                finishWithResult()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                message = "清除失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    private fun finishWithResult() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    @Deprecated("targetSdk 29 的返回语义：未在处理中时按完成返回")
    override fun onBackPressed() {
        if (!busy) {
            setResult(Activity.RESULT_OK)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
