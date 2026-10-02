# 当前状态与"下一步"清单

> 给下一次继续做的人（或 AI）看的交接说明，不参与构建。
>
> 仓库里**只有安卓版**（`android/`）。早期试过的 ArkTS/HAP 与 C# PC 服务端两套方案
> 已删除，不要再去找它们的文件。

## 已完成并实测通过

### 1. 手表端 App（`android/`，Java）

手工构建链（不需要 Gradle）：`aapt2 → javac --release 11 → d8 → zipalign → apksigner`，
封装在 `tools/build-apk.ps1`；安装由 `tools/install-apk.ps1` 负责，
内部处理华为手表"禁止 ADB 装第三方应用"的限制（临时冻结 `com.android.packageinstaller`）。

| 模块 | 职责 |
| --- | --- |
| `MainActivity` | 书库：扫描公共目录 + App 私有目录、进度百分比、长按菜单、表冠滚动 |
| `ReaderActivity` | 阅读页：自绘菜单/目录、进度落盘、侧键确认 |
| `ReaderView` | 自绘阅读视图：圆屏弦长安全区、表冠手势、自绘浮层菜单 |
| `Paginator` / `PageMap` | 分页几何 + 精确页码↔行号映射 |
| `Book` / `TextDecoder` | 解码（BOM/UTF-8/UTF-16/GB18030）+ 行索引 + 落盘缓存 |
| `KeepAwake` | 息屏保持 Wi-Fi（WifiLock） |

关键实测数据（一本 2.11MB / 848,765 字小说）：

- 首次建索引 0.6~0.7 秒；**二次打开 0 ms**（索引缓存命中）
- 正向 15 次连翻页码严格 `0→15`，行区间无缝衔接；反向同样严格 −1
- 圆屏：截图逐像素验证 **圆外亮像素 0**
- 表冠：4 段手势符号纯净度 **100%**（滑动窗口多数投票 + 换向滞后）

### 2. 工具脚本（`tools/`）

| 脚本 | 用途 |
| --- | --- |
| `adb-session.ps1` | 会话制"保持亮屏 + 保持 Wi-Fi"，`stop` 按原值还原 |
| `build-apk.ps1` / `install-apk.ps1` | 构建 / 安装（安装会自动 start 会话） |
| `probe-screen.ps1` | 截图程序化测量屏幕/菜单几何（排坐标问题用，别再靠猜） |
| `test-keepwifi.ps1` | 自动验证 App 内"息屏保持 Wi-Fi"开关 |
| `wifi-stay-awake.ps1` + `切换手表WiFi休眠.bat` | 系统 Wi-Fi 休眠策略切换（含双击菜单） |
| `clean.ps1` | 清理可再生产物（默认预览，`-Apply` 才删） |

---

## 未完成 / 需要接手验证

### A. 表上的手感类项（已实现并部署，待确认）

1. **菜单触摸上下滑动** —— 拖拽位移选中项；滑动过再抬手不会误触发选中
2. **目录定位当前章节** —— 长按正文打开目录时应停在你正在读的那一章
3. **底部「确定」按钮 / 侧键** —— 表冠选完需要显式确认；侧键 `KEY_F26` 也已接上
4. **圆屏正文宽度** —— 当前取弦长 ×0.98；要更宽/更窄就改
   `ReaderView.ensureMetrics()` 里的这个系数

### B. 值得继续做的工程项

1. **章节识别**目前是启发式正则，遇到非常规标题会退化成"整本 1 章"
2. **App 内开关的持久性**：`KeepAwake` 的锁只在进程存活期间有效，退出 App 即释放。
   要"彻底不依赖 App"就用 `wifi-stay-awake.ps1`（系统级）
3. **APK 图标**仍是脚本生成的占位图，上架前需替换
4. **单本上限**：正文常驻内存（约 2 字节/字），建议 ≤ 20MB（`LocalLibrary.MAX_BOOK_BYTES` 可调）

---

## 踩过的坑（都已修，别重犯）

### 设备与系统

- **还原 `wifi_sleep_policy` 为 1 会让无线 adb 立刻断线**，之后连"恢复"都做不到
  （实测把表弄成不可达，只能人工点亮手表）。所以 `adb-session.ps1 stop` 默认不还原这一项
- **`svc power stayon true/false` 会把 `stay_on_while_plugged_in` 清成 0**，
  会冲掉会话设好的值。保持亮屏统一由 `adb-session.ps1` 负责
- **别用 `input swipe x y x y 800` 模拟长按**，会被当成滑动；长按要用别的办法
- **华为手表会丢弃含中文的 logcat 行**（`Log.e` 的中文日志实测看不到）→ 诊断输出用英文

### 脚本与工具链

- **PowerShell 脚本必须存成 UTF-8 with BOM**，否则 PS 5.1 把中文解析成乱码。
  `write`/`edit` 工具写出的是无 BOM，需补：
  `[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($true)))`
- **`Select-String` 默认按 ANSI 读文件**，用 UTF-8 存的源码会读成乱码 → 用它搜中文会误判
- **同名函数互相覆盖**：两个 ps1 都定义 `Invoke-OnDevice` 时，点源后后者覆盖前者，
  导致命令静默未执行。会话脚本的函数已统一加 `Session-` 前缀
- **别用 `IndexOf('}')` 找方法结尾**去删代码，会定位到 lambda 的括号并破坏文件
- **`dumpsys | grep` 会产生无害的 "Broken pipe" 噪音**，重定向 stderr 即可

### 表冠（最容易踩）

- 表冠值是**位置/速度偏移，不是累积转动量**，一次手势内相邻差值符号会反复反转（机械回弹）
  → 必须用"滑动窗口多数投票 + 换向滞后"，用单次符号或差值判方向都会随机
- 触摸守卫若在每次触摸事件都刷新，会吞掉第一段旋转 → 只在 `ACTION_UP` 刷新，
  并在收到有效旋转样本时立即解除
- 表冠事件只派发给**当前有焦点**的 View → 书库列表要在 Activity 层拦截
  `dispatchGenericMotionEvent`，否则焦点在按钮上时收不到
- 系统 `AlertDialog` 收不到表冠事件（它在自己的窗口里）→ 菜单/目录必须自绘浮层

---

## 快速自检命令

```powershell
cd <本项目目录>

# 连接 + 开始保持会话（亮屏 + Wi-Fi 不休眠）
& .\tools\adb-session.ps1 start

# 构建 + 安装 + 启动
& .\tools\build-apk.ps1
& .\tools\install-apk.ps1
adb shell am start -n com.watchreader/.MainActivity

# 看当前屏幕/菜单几何（排坐标问题用）
& .\tools\probe-screen.ps1

# 清理可再生产物
& .\tools\clean.ps1            # 预览
& .\tools\clean.ps1 -Apply     # 执行

# 结束工作：还原（默认保留 Wi-Fi 策略，避免断连）
& .\tools\adb-session.ps1 stop
```
