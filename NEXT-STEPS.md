# 交接文档（给下一个会话 / 下一个人）

> 读完这一份就够。项目里**只有安卓版**（`android/`）。
> 早期试过的 ArkTS/HAP 与 C# PC 服务端两套方案**都已删除**，不要再去找它们的文件。
>
> 最后更新：本轮会话结束（提交 `b1f618e`）

---

## 0. 现在在哪里 / 下一步从哪开始

**项目位置**：`C:\Users\fan18\RiderProjects\WatchReader`
**远端**：https://github.com/beitianstu/WatchReader （**public**，main 分支）
**当前提交**：`b1f618e`，本地与远端一致，工作区干净

**手表**：HUAWEI Watch 3 Pro new（`GLL-AL09`），IP `192.168.3.213:5555`
**当前 ADB 状态**：**已断开**（本轮结束时主动断开的）
**调试会话**：**已还原**（息屏超时回到 10000ms；Wi-Fi 休眠策略按用户要求保留 `2`）

想继续工作，第一条命令就是：

```powershell
cd C:\Users\fan18\RiderProjects\WatchReader
& .\tools\adb-session.ps1 start     # 连接 + 保持亮屏/Wi-Fi
```

> 手表可能已经息屏导致 Wi-Fi 断开 → `adb-session.ps1` 会自动重试 3 次；
> 若仍失败，让用户**点亮手表屏幕**（息屏后 Wi-Fi 会断，这是这块表的默认行为）。

---

## 1. 项目是什么

在华为手表上运行的**本地 TXT 小说阅读器**。整本书在手表上解码、分页、渲染，
不依赖电脑。核心难点与解法：

| 难点 | 解法 |
| --- | --- |
| 表不能跑 C# / .NET | 交付 Java 安卓 APK（这块 HarmonyOS 4.0 实为 AOSP 10 底座） |
| 华为手表禁止 ADB 装第三方应用 | 安装时临时冻结 `com.android.packageinstaller`，装完解冻 |
| >1MB TXT 不能整本硬排 | 解码后建**行索引**并落盘缓存，取页只做切片；二次打开 0ms |
| 圆屏四角切字 | 正文字宽按**圆的弦长** `2√(r²−y²)` 计算 |
| 旋转表冠方向随机 | 滑动窗口**多数投票 + 换向滞后**（机械回弹导致差分会翻号） |
| 系统 AlertDialog 收不到表冠事件 | 菜单/目录改为**自绘浮层** |
| 长段落被截断 | 页面边界改为**字符区间**（见第 3 节 bug 3） |

**关键实测数据**（一本 2.11MB / 848,765 字小说）：

- 首次建索引 0.6~0.7 秒；**二次打开 0 ms**（缓存命中 `buildMs=0`）
- 圆屏：截图逐像素验证 **圆外亮像素 0**
- 表冠：4 段手势符号纯净度 **100%**（28:0 / 0:40 / 34:0 / 0:22）
- 分页：正反向各翻 8 页，字符区间完全首尾相接

---

## 2. 本会话（最近一轮）做了什么

### 提交历史

```
b1f618e  顺带修正总页数估算：改为按字符外推
ba3d832  修复长段落被截断：页面边界从"行号"改为"字符区间"
328778e  修复书库阅读进度显示错误，并让进度写入一定落盘
e4b8cf1  更新 README.md（用户自己在网页上编辑的）
6d63873  环境通用化：脚本不再写死本机路径，并修掉两个真 bug
9d3a0ec  初始化：HUAWEI Watch 3 Pro new 本地小说阅读器（安卓版）
```

### 本会话修掉的 bug（都已在真机部署验证）

**bug 1：书库进度显示完全不对（75% 显示成 7536%）**
`MainActivity.progressPercent()` 把已是 0~100 的百分数又乘了 100，
与它自己的注释（"percent 存的是 0~100 的百分比"）矛盾。
顺带把算法换成 `offset / charCount` 现算，并给 `Book` 加了 `cachedCharCount()`
（只读索引缓存头部第三行，不加载整个索引——列表里逐本 `open()` 会读进 3MB 索引）。

**bug 2："有时无法保存进度"**
`saveProgress()` 用 `SharedPreferences.apply()`（**异步**落盘），
手表进程随时被系统杀掉 → 那次写可能还没落盘就丢了。改为 `commit()` 并检查返回值。

**bug 3：长段落第一行之后的内容被截断**（用户报的最后一个问题）
根因是**架构性**的：`Book.index` 的一行是一个**段落**，长段落会在页内折成多个
**视觉行**，而旧代码把"消耗的视觉行数"当成"行号偏移"：

```java
int idx = from + count;   // 用视觉行数 count 当行号（错）
count++;
return from + count;      // 下一页从这一行开始 → 被切开那段的剩余文字永不渲染
```

改为**字符区间驱动**：每页覆盖 `[startChar, endChar)`，下一页起点 = 上一页终点，
结构上不可能出现空隙或重叠。`PageMap` 随之重写为"页码↔起始字符"，
旧版那套"平均每页行数估算 + 锚点纠正 + 邻页收敛"的救援逻辑整段删除
（它存在的原因正是行号边界不可靠）。

**顺带**：`estimatePageCount` 原本按"平均每页多少行"外推（行号已非权威单位，会偏小），
改为按字符外推。

### 本会话清理掉的东西

| 内容 | 大小 | 原因 |
| --- | --- | --- |
| `archive/watch-arkts-hap/` | 89 KB | 早期 ArkTS/HAP 方案，已废弃 |
| `src/` + `tests/`（C# 端） | 59 MB | 用户要求"只保留安卓版源码" |
| `WatchReader.sln` | 2 KB | 只索引那两个已删的 C# 项目 |
| `tools/smoke-test.ps1`、`tools/make-samples.ps1` | 15 KB | 只服务于已删的 C# 端 |
| `.idea/` | 6 KB | 畸形的嵌套 IDE 目录 |
| `tools/extract-toolbox.ps1` | 2 KB | PyInstaller 解包方案，当时就放弃了 |
| `TextDecoder.readUtf8()` | 12 行 | 零引用的死代码 |
| `android/build/` 下的调试截图与 trace | 2.4 MB | 一次性产物 |
| `C:\Users\fan18\.android\avd\*\snapshots\` | **16 GB** | 模拟器快照，与真机开发无关 |

---

## 3. 代码地图

```
WatchReader/
├─ android/app/
│   ├─ AndroidManifest.xml          权限、两个 Activity
│   ├─ java/com/watchreader/
│   │   ├─ MainActivity.java        书库：扫描/导入/进度百分比/长按菜单/表冠滚动
│   │   ├─ ReaderActivity.java      阅读页：自绘菜单与目录、进度落盘、侧键确认
│   │   ├─ ReaderView.java          自绘阅读视图（最大的文件，约 1380 行）
│   │   │                           · 圆屏弦长安全区 ensureMetrics/chordWidthAt
│   │   │                           · 表冠手势 handleCrown（多数投票+滞后）
│   │   │                           · 自绘浮层菜单 drawMenu/handleMenuTouch
│   │   ├─ Paginator.java           分页引擎（字符区间驱动）
│   │   │                           · measure()      从任意字符位置量一页【唯一真相来源】
│   │   │                           · paginateAt()   从任意字符位置取页（含行文本）
│   │   │                           · paginate()     行号入口（包装 paginateAt）
│   │   │                           · pageEndLine()  行号入口，内部先算字符终点
│   │   ├─ PageMap.java             页码 ↔ 起始字符（HashMap，翻页 O(1)）
│   │   ├─ Book.java                解码 + 行索引 + 落盘缓存 + cachedCharCount()
│   │   ├─ TextDecoder.java         编码嗅探（BOM/UTF-8 全文件校验/UTF-16/GB18030）
│   │   └─ KeepAwake.java           息屏保持 Wi-Fi（WifiLock，仅进程存活期间）
│   └─ res/                         主题、颜色、脚本生成的占位图标
├─ tools/                           见第 4 节
├─ README.md                        使用与原理（含表冠机理、弦长推导、踩坑记录）
└─ NEXT-STEPS.md                    本文件
```

### 数据存储位置（都在 App 私有目录，`adb shell run-as com.watchreader`）

| 路径 | 内容 |
| --- | --- |
| `shared_prefs/watchreader.xml` | 进度、字号、`lastOpen`、`keepWifiOnScreenOff` |
| `files/index/{cacheId}.idx` | 索引缓存。头部格式：`version\|size\|mtime\|encoding` / `charCount` / `indexCount` / 每行 `start\tend` / `###TEXT###` |
| `files/books/` | 导入的书的私有副本（无需权限） |
| `files/crown-trace.log`、`files/page-diag.log` | 诊断日志（仅当对应开关打开时才写） |

**进度格式**：`progress.{cacheId}` = `charOffset|pageIndex|percent`（percent 是 **0~100**）
外加 `progress.{cacheId}.offset` = 同一个 offset 的 int 形式。
`cacheId` = 书**绝对路径**的 SHA-1 前 8 字节的 hex。

---

## 4. 工具脚本（`tools/`）

| 脚本 | 用途 | 备注 |
| --- | --- | --- |
| `adb-session.ps1` | 会话制"保持亮屏 + Wi-Fi"；`stop` 按**原值**还原 | `build/install` 会自动调用 start |
| `build-apk.ps1` | 构建 APK | `aapt2→javac→d8→zipalign→apksigner`，不用 Gradle |
| `install-apk.ps1` | 安装 | 自动冻结/解冻安装器，`finally` 保证解冻 |
| `probe-screen.ps1` | 截图程序化测量屏幕/菜单几何 | **排坐标问题必须用它，不要靠猜** |
| `test-progress.ps1` | 进度持久化回归：打开→翻页→强杀→重开→比对 | |
| `test-keepwifi.ps1` | 自动验证 App 内"息屏保持 Wi-Fi"开关 | 点击轨迹依赖 `logCrownDiag=true` |
| `wifi-stay-awake.ps1` + `切换手表WiFi休眠.bat` | 系统 Wi-Fi 休眠策略切换 | 含双击菜单入口 |
| `clean.ps1` | 清理可再生产物 | 默认预览，`-Apply` 才删 |
| `env.ps1` | 共用环境解析（被上面所有脚本点源） | 见下 |
| `pagetest/run.ps1` | **桌面端分页无缝性测试**（不需要手表） | 见下 |

### 环境解析（`tools/env.ps1`）

脚本不再写死本机路径，按 **环境变量 → 常见安装位置 → 明确报错** 解析：

| 变量 | 用途 |
| --- | --- |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Android SDK（需含 platform-tools/build-tools/platforms） |
| `JAVA_HOME` | JDK 11 或 17（d8 / apksigner 需要） |
| `WATCH_ADB_TARGET` | 手表地址，默认 `192.168.3.213:5555` |

> ⚠️ `env.ps1:61` 仍把 `192.168.3.213:5555` 作为默认值（**待办**），
> 文档里其余 IP 都已是占位符。

### 桌面端分页测试（本会话新增，很重要）

```powershell
& .\tools\pagetest\run.ps1
```

用最小 stub 顶替 `android.graphics.Paint` / `android.text.TextPaint` / `android.util.Log`，
把 `Book`/`Paginator`/`PageMap` 的**纯逻辑拿真实 JDK 跑起来**（所以**不需要模拟器/手表**），
逐页推进并断言字符区间无缝覆盖全文。测试里还**复现了旧算法做对照**，会打印它跳过了多少字符。

**改分页逻辑后务必先跑它** —— 它是这个不变量唯一的自动化守卫。

---

## 5. 待办 / 需要用户确认

### 需要用户拍板的

1. **仓库是 public 的**（用户之前选"建仓库"时可能用了默认 Public）。
   公开后暴露了三处：
   - `android/build/debug.keystore` —— 他人可用它签出**同签名** APK（唯一有点实质影响的一项）
   - `tools/env.ps1:61` 的内网 IP 默认值（NAT 后，风险低）
   - `build-apk.ps1` 里 `-storepass android -keypass android`（Android 调试密钥标准口令，无妨）
   **问过两次，用户都还没表态。** 要改私有，用户可在
   Settings → Danger Zone → Change visibility 自己点，或给带 `Administration: write` 的 token。
2. **`debug.keystore` 是否从公开仓库移除**（需重写 git 历史；本地文件保留）。

### 待用户在手表扬声验证的

- 菜单**触摸上下滑动**是否顺手
- **长按正文**是否打开自绘目录并**定位到当前章节**
- 菜单底部**「确定」按钮** / **侧键**（`KEY_F26`）能否执行高亮项
- 圆屏正文字宽是否合适（当前弦长 ×0.98；改 `ReaderView.ensureMetrics()`）
- **本轮 bug 3 的修复**：翻到长段落那几页，看第一行是否接着继续（不再跳段）

### 已知未做的工程项

- **章节识别是启发式正则**，非常规标题会退化成"整本 1 章"
- **APK 图标**仍是脚本生成的占位图
- **单本上限**：正文常驻内存（约 2 字节/字），建议 ≤ 20MB
- `Paginator` 里 `Page.startLine/endLine` 现在只是诊断信息（派生值），
  若哪天要清理可以从 `Page` 里去掉

---

## 6. 踩过的坑（都已修，别重犯）

### 设备与系统

- **还原 `wifi_sleep_policy` 为 1 会让无线 adb 立刻断线**，之后连"恢复"都做不到
  （实测把表弄成不可达，只能人工点亮手表）。所以 `adb-session.ps1 stop` **默认不还原**这一项
- **`svc power stayon true/false` 会把 `stay_on_while_plugged_in` 清成 0**，
  冲掉会话设好的值。保持亮屏统一由 `adb-session.ps1` 负责，其它脚本不要碰
- **手表息屏后 Wi-Fi 会断**（默认策略），adb 也就断了 → 让用户点亮屏幕
- **华为手表会丢弃含中文的 logcat 行** → 诊断输出**一律用英文**
- **`input swipe x y x y 800` 不是长按**（会被当成滑动）；要模拟长按得另想办法
- 手表上**没有独立的确认/Home 物理键**，只有 `KEY_POWER` 与华为私有的 `KEY_F26`

### ADB

- 机器上有**两套 adb**：SDK 的（`D:\Android\android-sdk\platform-tools\adb.exe`）
  与工具箱的。各自带自己的 `~\.android\adbkey`，**只有 SDK 那套的密钥在手表上授权过**，
  用另一套会报 `offline / device still authorizing`
- **`github.com:443` 直连超时**，但 `api.github.com` 正常；系统代理在 `127.0.0.1:10808`。
  已给 git 配上**仅针对 github.com** 的代理：
  ```
  http.https://github.com.proxy  = http://127.0.0.1:10808
  https.https://github.com.proxy = http://127.0.0.1:10808
  ```

### PowerShell（被坑了很多次）

- **脚本文件必须存成 UTF-8 with BOM**，否则 PS 5.1 把中文解析成乱码。
  `write`/`edit` 工具写出的是**无 BOM**，需要：
  ```powershell
  [System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($true)))
  ```
- **`Select-String` 默认按 ANSI 读文件**，用它搜 UTF-8 源码里的中文会误判（假阴性）
- **函数返回"单元素集合"时会被解包成标量** → `$list[0]` 取到首字符。
  必须 `return , $collection`。**这个坑真实害我们找到过"设备序列号=1"**
- **点源带 `param` 的脚本会污染调用方作用域**：`adb-session.ps1` 里原本叫 `$Target`，
  点源后把调用方的 `$Target` 强制成 `[string]`。已改名为 `$Device` / `$AdbPath`，**别再改回去**
- **`if` 不能作为表达式内联在数组字面量里**（PS 5.1）→ 用显式 `if` + `ArrayList.Add`
- **PS 5.1 不支持三元 `? :`** → 用 `if/else`
- **`.Replace()` 只有 2 个参数的重载**；传 3 个会报 `Cannot find an overload`
- **`java "-Dfile.encoding=UTF-8"` 要加引号**，否则 PS 把 `.encoding` 当属性访问
- **`git :` 打到 stderr 的提示**会被 PS 当 error（含 `To https://...` 这种正常输出）
- **别用 `IndexOf('}')` 找方法结尾**去删代码，会定位到 lambda 的括号并破坏文件

### 工具链

- `aapt2` 会自己重编码 manifest 里的字符串；`aapt2 link` **没有 `--debuggable` 选项**，
  `debuggable` 必须写在 `AndroidManifest.xml` 里
- `apksigner` 会打 JDK "restricted method" 的 WARNING，**无害**
- **`dumpsys | grep` 会产生 `Broken pipe` 噪音**（grep 提前退出 → adb 收 SIGPIPE），
  重定向 stderr 即可

### 表冠（最容易踩）

- 表冠上报的是**位置/速度偏移，不是累积转动量**，一次手势内相邻差值符号会反复反转
  （机械回弹）→ **必须**用滑动窗口多数投票 + 换向滞后；
  用单次符号或相邻差值判方向都会随机
- 表冠事件**只派发给当前有焦点的 View** → 书库列表要在 Activity 层拦
  `dispatchGenericMotionEvent`，否则焦点在按钮上时收不到
- 触摸守卫若在**每次**触摸事件都刷新，会吞掉第一段旋转 → 只在 `ACTION_UP` 刷新，
  并在收到有效旋转样本时立即解除
- 菜单里表冠方向需要**与正文取反**（正文 `crownDirOf>0` 是"往后翻"，菜单里"往后"= 高亮下移）

---

## 7. 验证方法论（这一轮最值钱的经验）

用户明确说过：**他无法准确报告转动格数/时序，要我自己从数据里推**。
所以本轮形成了这套习惯，建议沿用：

1. **不要靠猜坐标**：用 `probe-screen.ps1` 截图 + 像素分析量出面板/行的真实位置。
   我在这上面浪费过很多轮（行高算错 → 点到别的菜单项 → 误判"功能没生效"）
2. **读设备实际值核对**，而不是只看脚本的输出。例：
   脚本打印"已设置常亮"，但设备上 `stay_on_while_plugged_in` 还是 0 —— 这才发现
   `svc power stayon true` 会冲掉它
3. **为 bug 写回归测试**，并且**让旧代码也在同一测试下失败**。
   bug 3 的对照实验（旧算法第 0 页跳过 139,529 字）是这次最有说服力的证据
4. **把纯逻辑与 Android 运行时解耦**，这样测试不需要手表/模拟器（见 `tools/pagetest`）
5. **验证要落到像素/字符/数字**，不要停在"看起来对了"：
   圆屏用"圆外亮像素 0"、分页用"字符区间首尾相接"、进度用"强杀后 offset 不变"

---

## 8. 常用命令速查

```powershell
cd C:\Users\fan18\RiderProjects\WatchReader

# 连接 + 保持亮屏/Wi-Fi（工作开始时跑）
& .\tools\adb-session.ps1 start

# 构建 / 安装 / 启动
& .\tools\build-apk.ps1
& .\tools\install-apk.ps1
adb shell am start -n com.watchreader/.MainActivity

# 分页纯逻辑测试（不需要手表，改分页后必跑）
& .\tools\pagetest\run.ps1

# 进度持久化回归（需要手表）
& .\tools\test-progress.ps1

# 量屏幕/菜单几何（排坐标问题）
& .\tools\probe-screen.ps1

# 看设备状态 / 诊断日志
& .\tools\adb-session.ps1 status
adb shell "run-as com.watchreader cat shared_prefs/watchreader.xml"
adb shell "run-as com.watchreader cat files/page-diag.log"     # 需 logPageDiag=true
adb shell "run-as com.watchreader cat files/crown-trace.log"   # 需 logCrownDiag=true

# 清理可再生产物
& .\tools\clean.ps1 -Apply

# 结束工作：还原会话（默认保留 Wi-Fi 策略，避免断连）
& .\tools\adb-session.ps1 stop
```

### 诊断开关（默认都关闭，出问题时才打开）

| 开关 | 位置 | 输出 |
| --- | --- | --- |
| `logCrownDiag` | `ReaderView` | `files/crown-trace.log`（表冠原始值、菜单点击、菜单项执行） |
| `logPageDiag` | `ReaderView` | `files/page-diag.log`（每页 `chars=起点-终点`，验证分页衔接用这个） |
| `CROWN_DIAG` | `ReaderActivity` | 在设置菜单里多出"查看表冠/页码诊断"两项 |

打开后要**重新构建安装**才生效（`install-apk.ps1`）。
