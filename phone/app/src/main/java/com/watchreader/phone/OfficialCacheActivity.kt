package com.watchreader.phone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.FileInputStream
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiCompactTitleScaffold
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.rememberMochiNavigator
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.utils.mochiEnableEdgeToEdge

/**
 * 官方番茄缓存导入页。
 *
 * 只读 root 私有缓存：扫描、进度、取消都在后台线程；无 root 时明确显示不可用并允许退出。
 * 本页仅请求已有 root 的正常授权，不安装 root，也不展示用户 ID、密钥或正文。FLAG_SECURE 防止缓存列表被系统截图。
 */
class OfficialCacheActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        mochiEnableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ReadingAppTheme {
                ProvideMochiOverscroll {
                    val nav = rememberMochiNavigator("official-cache")
                    MochiNavHost(nav, Modifier.fillMaxSize()) {
                        OfficialCachePage(
                            onClose = { finish() },
                            onImported = {
                                setResult(Activity.RESULT_OK)
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface CacheUiState {
    data object Checking : CacheUiState
    data object NoRoot : CacheUiState
    data class Ready(val books: List<OfficialCache.CacheBook>) : CacheUiState
    data class Failed(val message: String) : CacheUiState
}

@Composable
private fun OfficialCachePage(onClose: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }

    var state by remember { mutableStateOf<CacheUiState>(CacheUiState.Checking) }
    var selected by remember { mutableStateOf<OfficialCache.CacheBook?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun postProgress(text: String) {
        handler.post { progress = text }
    }

    fun scan() {
        if (busy) return
        busy = true
        selected = null
        message = null
        state = CacheUiState.Checking
        progress = "正在检查 root…"
        job = scope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    OfficialCache.scan { text -> postProgress(text) }
                }
                state = if (result.rootAvailable) {
                    CacheUiState.Ready(result.books)
                } else {
                    CacheUiState.NoRoot
                }
                message = result.message
            } catch (cancelled: CancellationException) {
                state = CacheUiState.Failed("已取消扫描")
                throw cancelled
            } catch (ignored: Throwable) {
                state = CacheUiState.Failed("扫描失败，请稍后重试")
            } finally {
                busy = false
                progress = ""
            }
        }
    }

    fun startImport(book: OfficialCache.CacheBook) {
        if (busy) return
        busy = true
        message = null
        progress = "准备读取缓存…"
        job = scope.launch {
            var result: OfficialCache.ImportResult? = null
            try {
                val imported = runInterruptible(Dispatchers.IO) {
                    OfficialCache.importBook(context, book) { text -> postProgress(text) }
                }
                result = imported
                runInterruptible(Dispatchers.IO) {
                    try {
                        FileInputStream(imported.textFile).use { input ->
                            LibraryFiles.importFrom(
                                input,
                                PhoneLibrary.booksDir(context),
                                LibraryFiles.safeName(book.name),
                            )
                        }
                    } finally {
                        imported.textFile.delete()
                    }
                }
                Toast.makeText(
                    context,
                    "已导入《${book.name}》\n${importSummary(imported)}",
                    Toast.LENGTH_LONG,
                ).show()
                onImported()
            } catch (cancelled: CancellationException) {
                result?.textFile?.delete()
                message = "已取消，未导入任何书籍"
                throw cancelled
            } catch (interrupted: InterruptedIOException) {
                result?.textFile?.delete()
                message = "已取消，未导入任何书籍"
            } catch (error: OfficialCache.CacheException) {
                result?.textFile?.delete()
                message = error.message ?: "导入失败"
            } catch (ignored: Throwable) {
                result?.textFile?.delete()
                message = "导入失败，请稍后重试"
            } finally {
                busy = false
                progress = ""
            }
        }
    }

    LaunchedEffect(Unit) { scan() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    MochiCompactTitleScaffold(
        title = "番茄缓存导入",
        subtitle = progress.ifEmpty { "只读官方 App 缓存" },
        actions = {
            MochiBarButton(text = "关闭", onClick = onClose)
        },
    ) {
        when (val current = state) {
            CacheUiState.Checking -> {
                MochiListSection(header = "扫描") {
                    MochiListRow(
                        title = progress.ifEmpty { "正在扫描官方缓存…" },
                        loading = true,
                    )
                }
                if (busy) {
                    SectionButton(text = "取消扫描", onClick = { job?.cancel() })
                }
            }

            CacheUiState.NoRoot -> {
                MochiListSection(header = "不可用") {
                    MochiListRow(
                        title = "未检测到 root",
                        subtitle = "此功能只读取番茄官方 App 已下载的缓存，需要 root；不会请求安装 root，也不会修改官方 App 数据。",
                    )
                }
                SectionButton(text = "退出", onClick = onClose)
            }

            is CacheUiState.Failed -> {
                MochiListSection(header = "无法读取") {
                    MochiListRow(
                        title = current.message,
                        subtitle = "需要 root 才能读取官方 App 私有缓存。",
                    )
                }
                SectionButton(text = "重试", onClick = { scan() })
                SectionButton(text = "退出", onClick = onClose)
            }

            is CacheUiState.Ready -> {
                if (current.books.isEmpty()) {
                    val emptyTitle = message ?: "未找到已下载的书籍缓存"
                    val emptySubtitle = if (message != null) {
                        "请确认 root 权限可用后重试。"
                    } else {
                        "请先在番茄官方 App 中下载章节，再回来重试。"
                    }
                    MochiListSection(header = "官方缓存") {
                        MochiListRow(
                            title = emptyTitle,
                            subtitle = emptySubtitle,
                        )
                    }
                    SectionButton(text = "重新扫描", onClick = { scan() })
                    SectionButton(text = "退出", onClick = onClose)
                } else {
                    MochiListSection(header = "官方缓存书籍（${current.books.size}）") {
                        current.books.forEach { book ->
                            MochiListRow(
                                title = book.name,
                                subtitle = "${book.fileCount} 个缓存文件",
                                chevron = true,
                                enabled = !busy,
                                onClick = {
                                    selected = book
                                    message = null
                                },
                            )
                        }
                    }

                    val book = selected
                    if (book != null && !busy) {
                        MochiListSection(header = "导入到手机书库") {
                            MochiListRow(
                                title = book.name,
                                subtitle = "只导入已下载且能解密的章节；不覆盖同名书。",
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MochiButton(
                                    onClick = { startImport(book) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("导入这本书")
                                }
                                MochiButton(
                                    onClick = { selected = null },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("取消选择")
                                }
                            }
                        }
                    }

                    if (busy) {
                        MochiListSection(header = "导入中") {
                            MochiListRow(
                                title = progress.ifEmpty { "正在导入…" },
                                loading = true,
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            ) {
                                MochiButton(
                                    onClick = { job?.cancel() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("取消导入")
                                }
                            }
                        }
                    }

                    message?.let { text ->
                        MochiListSection(header = "提示") {
                            MochiListRow(title = text)
                        }
                    }

                    SectionButton(text = "重新扫描", onClick = { scan() }, enabled = !busy)
                    SectionButton(text = "退出", onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun SectionButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        MochiButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text)
        }
    }
}

private fun importSummary(result: OfficialCache.ImportResult): String = buildString {
    append("成功 ").append(result.ok).append(" 章，失败 ").append(result.failed).append(" 章")
    if (result.missingKey > 0) append("（缺密钥 ").append(result.missingKey).append("）")
    if (result.corrupt > 0) append("（损坏 ").append(result.corrupt).append("）")
    if (result.duplicates > 0) append("（重复 ").append(result.duplicates).append("）")
    append("，可读缓存 ").append(result.readable).append(" 个")
    if (!result.orderReliable) append("；部分章节无法确定顺序，已按可用信息排列")
}
