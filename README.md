# WatchReader · HUAWEI Watch 3 Pro new 本地小说阅读器

设备：**HUAWEI Watch 3 Pro new（GLL-AL09 / HarmonyOS 4.0.0.408）**
屏幕：466×466 圆屏（`FLAG_ROUND`）、density 320
系统底座：**Android 10 / API 29**、armeabi-v7a（32 位）、1.8GB RAM

需求：能读 **超过 1MB 的 TXT**、能 **保存阅读进度**、整本书在手表上跑（不依赖电脑）。

---

## 一、先说清两件事（避免走错路）

### 1. 这块表**能跑安卓 APK**

实测 `adb shell getprop`：

| 属性 | 值 |
| --- | --- |
| `ro.build.fingerprint` | `HUAWEI/GLL-AL09CN/HWGLL:10/HUAWEIGLL-AL09CN/102.0.0.408C00` |
| `ro.build.version.sdk` / `release` | **29 / 10** |
| 内核 | Linux 4.14.116（真安卓内核） |
| `ro.product.cpu.abi` | armeabi-v7a |

即这块 HarmonyOS 4.0 手表是 **AOSP 10 底子**，所以：

- ✅ **可以直接装 APK** —— 但要绕过一道限制，见下一点

所以最终交付是 **Java 安卓 App**（`android/`）。
（早期还试过 ArkTS/HAP 与 C# PC 服务端两套方案，**都已删除**，仓库里只保留安卓版。）

### 2. 华为手表默认禁止 ADB 装第三方应用

直接 `adb install` 会失败：

```
Failure [INSTALL_FAILED_ABORTED: User rejected permissions]
W/PackageInstaller: InstallStart:try to install in watch, not allow install unknown apps
```

**解法：安装前临时冻结「应用安装器」，装完立刻解冻。**
[`tools/install-apk.ps1`](tools/install-apk.ps1) 已把"冻结 → 安装 → 解冻"封装好，
并用 `finally` 保证异常时也会解冻。

> ⚠️ 千万别忘了最后一步：**重启手表前必须解冻** `com.android.packageinstaller`，
> 否则系统自带的安装界面会不可用。手动命令：
> `adb shell pm enable --user 0 com.android.packageinstaller`

---

## 一之二、环境准备（脚本会自动找，也可显式指定）

工具脚本不再写死某一台机器的路径，按 **环境变量 → 常见安装位置 → 明确报错** 的顺序解析：

| 需要的东西 | 环境变量 | 说明 |
| --- | --- | --- |
| Android SDK | `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` | 需含 `platform-tools/adb.exe`、`build-tools`、`platforms` |
| JDK | `JAVA_HOME` | d8 / apksigner 需要 JDK 11 或 17 |
| 手表地址 | `WATCH_ADB_TARGET` | 无线调试地址，形如 `192.168.1.23:5555` |

```powershell
$env:ANDROID_HOME     = 'D:\Android\android-sdk'
$env:JAVA_HOME        = 'C:\Program Files\Java\jdk-17'
$env:WATCH_ADB_TARGET = '192.168.1.23:5555'
```

也可以逐次传参覆盖，例如：

```powershell
& .\tools\build-apk.ps1 -Sdk 'D:\sdk' -JdkHome 'C:\jdk17'
& .\tools\install-apk.ps1 -Apk 'D:\out\app.apk' -Target '192.168.1.23:5555'
```

找不到依赖时会直接打印解决办法（而不是抛一段看不懂的异常）。

## 二、快速开始

```powershell
cd <本项目目录>

# 0) 只做一次：设好环境变量（见上一节）
$env:ANDROID_HOME     = '<你的 Android SDK>'
$env:JAVA_HOME        = '<你的 JDK 17>'
$env:WATCH_ADB_TARGET = '<手表 IP>:5555'

# 1) 连手表（无线调试需先在手表开发者选项里打开"无线调试"）
& .\tools\adb-session.ps1 start

# 2) 构建 APK（无需 Gradle：aapt2 → javac → d8 → zipalign → apksigner）
& .\tools\build-apk.ps1

# 3) 安装（自动处理冻结/解冻安装器）
& .\tools\install-apk.ps1

# 4) 启动
adb shell am start -n com.watchreader/.MainActivity
```

---

## 三、操作方式（圆屏 + 一块旋转表冠）

| 操作 | 效果 |
| --- | --- |
| **旋转表冠** | 翻页（上拧往后、下拧往前）；菜单里移动选中项 |
| 点屏幕右侧 2/3 | 下一页 |
| 点屏幕左侧 1/3 | 上一页 |
| 左右滑动 | 翻页 |
| **长按正文** | 打开目录（**自动定位到你正在读的那一章**） |
| 点底部中央（页码处） | 阅读设置菜单 |
| 菜单内上下滑动 | 移动选中项 |
| 菜单底部「确定」/ 侧键 | 执行选中项（表冠只能选，需要显式确认） |

### 表冠的坑（都是实测标定的，改参数前先看这里）

这块表把表冠上报为独立设备 `/dev/input/event5`，名字就叫 `rotary_crown`，
Android 侧是 `ACTION_SCROLL(8)` + `source=0x400000(SOURCE_ROTARY_ENCODER)` + `AXIS_SCROLL`。

**关键：这个数值不是"累积转动量"，而是表冠在卡位之间的位置/速度偏移。**
实测轨迹（一次连续旋转）：

```
-0.065  -0.143  -0.143  -0.156  -0.143  -0.091  -0.065  -0.065  -0.039  -0.013
```

它围绕零点来回摆动，**相邻样本的差值符号在一次旋转内部就会反复反转**（机械回弹）。所以：

- ❌ 用「相邻差值」判方向 → 方向随机（最初的毛病，也和"某些应用里方向随机"一致）
- ❌ 用「单次原始值符号」判方向 → 回弹会短暂穿到另一侧
- ✅ 用**滑动窗口多数投票 + 换向滞后**：只有 `|raw| ≥ 0.02` 的样本投票，同向累积能量，
  攒够 `0.25` 翻一页；反向能量超过 `0.12` 才认定换向

实测 4 段手势符号纯净度 **100%**（28:0 / 0:40 / 34:0 / 0:22），方向不再随机。

---

## 三之二、调试会话：自动保持亮屏 + Wi-Fi

调试时最烦的两件事是"屏幕自己黑了"和"Wi-Fi 被系统省电关掉导致 adb 断"。
[`tools/adb-session.ps1`](tools/adb-session.ps1) 把这两件事做成会话制：

```powershell
& .\tools\adb-session.ps1 start      # 连表 + 保持亮屏/Wi-Fi（先记录原始值）
& .\tools\adb-session.ps1 status     # 查看当前状态
& .\tools\adb-session.ps1 stop       # 还原（默认保留 Wi-Fi 策略，见下）
```

开始时会做三件事（并先把**原始值**写进 `%TEMP%\watchreader-adb-session.json`）：

| 设置 | 值 | 作用 |
| --- | --- | --- |
| `system screen_off_timeout` | 1800000 ms | 屏幕 30 分钟不自动熄 |
| `global stay_on_while_plugged_in` | 7 | 插电/无线充电时常亮 |
| `global wifi_sleep_policy` | 2 | Wi-Fi 永不休眠 |

**`build-apk.ps1` / `install-apk.ps1` 会自动调用 start**（幂等，不会覆盖已有基线），
所以"每次连上 adb 就自动保持"是自动发生的，你只需在工作结束时跑一次 `stop`。

`stop` 的保证：

- **按原值还原** `screen_off_timeout` 与 `stay_on_while_plugged_in`（不是"猜默认值"）
- **默认不还原 `wifi_sleep_policy`** —— 因为还原成 `1`（息屏关 Wi-Fi）会让无线 adb
  **立刻断线**，之后就再也连不上、连"恢复"都无从操作。实测踩过这个坑：
  还原后手表直接变成不可达。确实要连这项一起还原就显式加 `-RestoreWifiPolicy`
- **幂等**：没有会话文件时不做任何改动，重复调用安全
- 连接失败会自动重试（含把 `offline` 状态断开重连），并在彻底失败时给出排查步骤

### 踩过的两个坑（都已在脚本里注掉）

1. **`svc power stayon true/false` 会把 `stay_on_while_plugged_in` 清成 0** ——
   安装脚本原本在开头/结尾各调了一次，结果把会话设好的 `7` 冲成 `0`。
   现在"保持"只由会话脚本负责，安装脚本不再碰它。
2. **同名函数会互相覆盖** —— `install-apk.ps1` 与 `adb-session.ps1` 原本都定义了
   `Invoke-OnDevice`，点源后安装脚本的定义覆盖了会话脚本的，导致会话脚本里那条命令
   实际没生效。现在会话脚本的函数统一加 `Session-` 前缀。

---

## 四、>1MB TXT 与阅读进度

### >1MB：一次折行建索引，之后取页 O(1)

- 解码后规范化，段首插 2 个全角缩进（**缩进计入行宽**，取页只做切片）
- 建**行索引** `[行首, 行尾)`，每行只做一次 `Paint.breakText` 测量
- **索引落盘缓存**（`files/index/{id}.idx`）：第二次打开 **`buildMs=0`**，秒开

实测一本 **2.11MB / 848,765 字** 的小说：

| 指标 | 值 |
| --- | --- |
| 首次建索引 | 约 0.6~0.7 秒（读盘 + 解码 + 折行） |
| 第二次打开（命中缓存） | **0 ms** |
| 每页 | 10 行 / 每行约 16 字 |

缓存带自洽校验：行区间必须递增、不越界、末行终止位置与正文长度吻合，
任一项不符就重建（防止"索引与正文错位导致整页空白"）。

### 进度：双坐标

| 存什么 | 为什么 |
| --- | --- |
| **字符偏移**（权威值） | 改字号后每页行数变了、页码会漂，字符偏移不会 |
| 页码 | 直接跳回用 |
| 百分比 | 书库列表显示"读到百分之几" |

每次翻页立即写入 `SharedPreferences`（手表随时可能被系统杀掉，不能等 `onPause`）。

### 分页不变量：页面按**字符区间**衔接，结构上不会漏字

**这是踩过的一个大坑。** 早期版本用"行号"当页面边界：

```java
// 错：book.index 的一行是一个**段落**，长段落会被拆成多个视觉行，
// 于是"消耗的视觉行数"≠"消耗的逻辑行数"，下一页从 endLine 行开始就会跳过剩余部分
return from + count;      // count 是视觉行数，却被当成行号偏移
```

表现为：**某一页的最后一段文字如果超过一行显示宽度，第一行之后的内容会被截断**
（实测：一段 14 万字的无换行段落，旧算法第 0 页就跳过了 139,529 字）。

根治方式是让页面边界就是**字符位置**：每页覆盖 `[startChar, endChar)`，
下一页的起点直接用上一页的终点。这样两页之间不可能出现空隙或重叠。

`PageMap` 也随之从"页码↔行号"改为"页码↔起始字符"，于是旧版那套
"平均每页行数估算页码 + 锚点纠正 + 邻页收敛"的救援逻辑整段删掉了
（它存在的原因正是行号边界不可靠，根因没了自然就不需要）。

这个不变量有**桌面端自动化测试**守着（不需要手表）：

```powershell
& .\tools\pagetest\run.ps1
```

它用最小 stub 顶替 `android.graphics.Paint` / `android.text.TextPaint`，
把 `Book`/`Paginator`/`PageMap` 的纯逻辑拿真实 JDK 跑起来，逐页推进并断言
区间无缝覆盖全文；同时**复现旧算法**做对照，输出它跳过了多少字符。

### 页码精确性（快速翻页不乱）

页码**不能**用"起始行 ÷ 平均每页行数"估算 —— 长段落一页只放 2 行、空行密集处十几行，
快速连翻时误差累积。这里用 `PageMap` 维护精确的 页码↔行号 映射：

- 翻页走 ±1 明确推进（不估算）
- 只有跳目录/百分比才用"锚点纠正 + 邻页收敛"定位

实测正向 15 次连翻：页码 `0→15` 严格 +1，行区间首尾无缝衔接（`8-18 → 18-26 → 26-36`）；
反向同样严格 −1，与正向记录逐字符吻合。

### 圆屏安全区

按圆的弦长算可用宽度：`弦长 = 2√(r² − y²)`，取正文区首行/末行里更差的那个 y；
顶栏/底栏也各按自身高度的弦长收窄。程序化验证：截图后逐像素算到圆心的距离，
**圆外亮像素 0 个**。

---

## 五、导入小说

App 会扫描这些位置（书库页会显示实际路径）：

1. **App 私有目录**（首选，**不需要任何权限**）
   `/data/data/com.watchreader/files/books/`
2. 公共目录：`/sdcard/Download`、`/sdcard/Books`、`/sdcard/novel` 等
   （需要 `READ_EXTERNAL_STORAGE`，首次进入会弹窗申请）

```powershell
# 方式 A：放进公共目录（最省事）
adb push "D:\小说\某本书.txt" /sdcard/Download/

# 方式 B：放进私有目录（无需权限，更可靠；debuggable 包支持 run-as）
adb push "D:\小说\某本书.txt" /sdcard/Download/tmp.txt
adb shell "run-as com.watchreader mkdir -p files/books"
adb shell "run-as com.watchreader sh -c 'cat /sdcard/Download/tmp.txt > files/books/某本书.txt'"
```

**编码要求**：支持 UTF-8（含 BOM）、UTF-16LE/BE、GB18030/GBK。
UTF-8 校验是**全文件严格校验**（早先只抽样 64KB，把一本合法 UTF-8 误判成 GBK，
整本显示成乱码 —— 该 bug 已修）。

---

## 六、息屏保持 Wi-Fi

手表默认 `wifi_sleep_policy=1`（熄屏即关 Wi-Fi，仅充电时保持），锁屏后无线 adb 会断。
两条路径：

| 方式 | 入口 | 生效范围 | 需要 App 运行 | 耗电 |
| --- | --- | --- | --- | --- |
| 系统策略 | [`tools/wifi-stay-awake.ps1`](tools/wifi-stay-awake.ps1) | 全局、持久 | 不需要 | 较高 |
| App 内开关 | 阅读设置 → 「息屏保持 Wi-Fi」 | 仅 App 进程存活 | 需要 | 较低 |

```powershell
& .\tools\wifi-stay-awake.ps1            # 设为永不休眠（wifi_sleep_policy=2）
& .\tools\wifi-stay-awake.ps1 -Verify    # 查看当前策略
& .\tools\wifi-stay-awake.ps1 -Policy 1  # 恢复默认
```

不想敲命令就双击 [`tools/切换手表WiFi休眠.bat`](tools/切换手表WiFi休眠.bat)（带菜单）。

**实测验证**（关闭屏幕后对比 Wi-Fi 休眠计时器）：

```
系统策略 = 2                      ：息屏 45 秒，休眠增量 0 ms
系统策略 = 1 + App 内 WifiLock    ：息屏 60 秒，休眠增量 0 ms
```


---

## 七、目录结构

```
WatchReader/
├─ android/                          ★ 手表 App（Java，手工构建，不用 Gradle）
│   ├─ app/AndroidManifest.xml       权限、页面声明
│   ├─ app/java/com/watchreader/
│   │   ├─ MainActivity.java         书库（扫描/导入/进度/长按删除 + 表冠滚动）
│   │   ├─ ReaderActivity.java        阅读页（菜单/目录/进度/表冠确认）
│   │   ├─ ReaderView.java           自绘阅读视图（圆屏安全区/表冠/自绘菜单）
│   │   ├─ Paginator.java            分页几何（pageEndLine / 每页行数）
│   │   ├─ PageMap.java              精确页码映射（页码↔行号）
│   │   ├─ Book.java                 解码 + 行索引 + 落盘缓存
│   │   ├─ TextDecoder.java          编码嗅探（BOM/UTF-8/UTF-16/GB18030）
│   │   └─ KeepAwake.java            息屏保持 Wi-Fi（WifiLock）
│   └─ app/res/                      主题/颜色/图标
├─ tools/
│   ├─ build-apk.ps1                 aapt2 → javac → d8 → zipalign → apksigner
│   ├─ install-apk.ps1               冻结/解冻"应用安装器"后安装
│   ├─ probe-screen.ps1              截图测量屏幕/菜单几何（调试用）
│   ├─ test-keepwifi.ps1             自动验证"息屏保持 Wi-Fi"开关
│   ├─ wifi-stay-awake.ps1           系统 Wi-Fi 休眠策略切换
│   ├─ clean.ps1                     清理可再生产物（默认预览，-Apply 才删）
│   ├─ 切换手表WiFi休眠.bat            上面脚本的双击菜单入口
│   ├─ probe-screen.ps1              截图测量屏幕/菜单几何（调试用）
│   ├─ test-keepwifi.ps1             自动验证"息屏保持 Wi-Fi"开关
│   ├─ wifi-stay-awake.ps1           系统 Wi-Fi 休眠策略切换
│   ├─ clean.ps1                     清理可再生产物（默认预览）
│   └─ 切换手表WiFi休眠.bat            上面脚本的双击菜单入口
├─ README.md                         本文档
├─ NEXT-STEPS.md                     交接说明（当前状态 / 待验证项 / 踩坑）
└─ .gitignore
```

---

## 八、清理可再生的东西

项目里 ~55MB 是**可再生的构建产物**（`bin/` `obj/` `android/build/{apk,classes,dex}`），
真正的源码+文档只有 ~5MB。清理：

```powershell
& .\tools\clean.ps1              # 预览：只列出会删什么
& .\tools\clean.ps1 -Apply       # 实际删除（APK 产物 + 调试遗留）
& .\tools\clean.ps1 -Apply -Deep # 连 APK/classes/dex 一起删（下次构建重新生成）
```

**默认永不删**：`android/build/debug.keystore`（签名密钥，删了会导致覆盖安装因签名不一致失败）、
所有源码/资源/文档/tools 脚本。
脚本对每个目标都做"解析后绝对路径必须仍在项目内"的校验，不会误删项目外的文件。

## 九、已知限制

1. **单本过大**：解码后正文常驻内存（约 2 字节/字），建议 ≤ 20MB。
2. **章节识别是启发式**：正则匹配 `第X章/节/回/卷`、`Chapter N`、`序章/楔子/番外` 等；
   匹配不到不报错，目录退化成"整本 1 章"。
3. **进度只在本机**：不跨设备同步（要同步就走第八节的 PC 服务端模式）。
4. **图标是占位图**（`android/app/res/mipmap-*/ic_launcher.png`），上架前需替换。
5. **表冠参数是这台表的实测值**：换其他型号需重新标定
   （`ReaderView` 里的 `CROWN_MIN_MAG` / `CROWN_PAGE_ACCUM` / `CROWN_UP_MEANS_FORWARD`）。
6. **诊断开关**：`ReaderView.logCrownDiag` / `logPageDiag`，默认关闭；
   排查表冠/页码问题时打开，会写 `files/crown-trace.log` 与 `files/page-diag.log`。

---

## 十、验证状态

| 项目 | 状态 |
| --- | --- |
| APK 构建（aapt2/javac/d8/apksigner） | ✅ 通过 |
| 安装到手表（含冻结/解冻安装器） | ✅ 通过 |
| 2.11MB / 848,765 字小说读取 | ✅ 建索引 0.6~0.7s；二次打开 **0ms** |
| 正/反向快速翻页页码连续性 | ✅ 严格 ±1，行区间无缝衔接 |
| 进度保存与恢复 | ✅ 字符偏移精确保留（改字号后仍回到同一处） |
| 圆屏安全区 | ✅ 圆外亮像素 0 |
| 旋转表冠方向 | ✅ 4 段手势符号纯净度 100% |
| 息屏保持 Wi-Fi | ✅ 系统策略与 App WifiLock 两种方式均实测通过 |
| 菜单触摸滑动 / 目录定位 / 底栏确认键 | ⚠️ 已实现并部署，待你在表上确认手感 |

---

## 十一、参考

- [华为手表安装第三方应用完整流程（含 Watch 3 系列）](https://blog.csdn.net/zhiyuan411/article/details/142623477)
- [MotionEvent.AXIS_SCROLL / 旋转输入](https://developer.android.com/reference/android/view/MotionEvent#AXIS_SCROLL)
- [WifiManager.WifiLock 与 WIFI_MODE_FULL_HIGH_PERF](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF)
- [Settings.Global.WIFI_SLEEP_POLICY](https://developer.android.com/reference/android/provider/Settings.Global#WIFI_SLEEP_POLICY)
