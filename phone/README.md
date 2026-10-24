# WatchReader 手机版（Mochi UI）

这是 WatchReader 的手机独立 Gradle 工程，界面按 [feice11/mochi-compose](https://github.com/feice11/mochi-compose) 的
[应用接入规范](https://github.com/feice11/mochi-compose/blob/51d08cdd4285b54a562f474882178f6ecab62e53/docs/zh_CN/guide/application-integration.md) 实现。
上游源码快照锁定 commit `51d08cdd4285b54a562f474882178f6ecab62e53`，位于 `third-party/mochi-compose`，
**不要修改**该目录；升级时整体替换并重新核对规范。

当前为手机版实验版本，已完成核心阅读流程验证，界面切换和书架性能仍需优化。此目录是独立 Gradle 工程，
但复用同一仓库的共享源码；请克隆完整 WatchReader 仓库，不要仅下载 `phone/`。

## 结构

| 模块 | 说明 |
|---|---|
| `app` | `applicationId=com.watchreader`。`PhoneMainActivity`（主页/番茄/设置共用底栏）、`FanqieController`（番茄全流程）、`PhoneReaderActivity`（本地阅读）；旧 `PhoneFanqieActivity` 是兼容重定向入口；直接引用 `../android/app/java`、`../fanqie/api/java`、`../fanqie/host/java`，不复制旧业务 |
| `plugin` | `applicationId=com.watchreader.fanqie`，复用 `../fanqie/android/java` 与 `../fanqie/api/java`；账号页换成 Mochi 版，组件名仍通过 `activity-alias` 保持 `com.watchreader.fanqie.PluginSetupActivity` |
| `shared-ui` | app/plugin共用阅读配色配置与Mochi语义主题，无账号或业务数据 |
| `third-party/mochi-compose` | 上游组件库源码（Apache-2.0），只读 |

## 手表兼容边界

- 手表继续用 `tools/build-apk.ps1` 的无 Gradle 构建，源码与产物路径不变。
- `ReaderView` 的矩形排版、隐藏自绘页眉页脚、Mochi 配色都默认关闭；只有手机 Manifest 的
  `com.watchreader.PHONE_UI=true` 且手机阅读页显式调用时才生效，手表公式、颜色与交互不变。
- 手机阅读页与旧 `ReaderActivity` 共用 `BookCache`，同一本书在两者之间来回打开不会重复建索引。
- 旧 `ReaderActivity` / `FanqieActivity` 仍保留并注册，但手机入口全部路由到新页面。

## 构建（JDK 17 + SDK 36 + Gradle 8.14.3）

```bat
cd WatchReader\phone
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug :plugin:assembleDebug
```

- `app` 使用现有 `../android/build/debug.keystore`；`plugin` 使用现有
  `../fanqie/build/local-signing.keystore` 与 `.password`。两者缺失都会直接失败，
  不生成替代密钥、不退回默认 debug 签名。
- 包名与旧版相同，只能覆盖升级同一台手机上的旧 WatchReader，**不能并装**。
- 番茄功能需要另装 `com.watchreader.fanqie` 插件；主程序与插件按证书互相校验。

## 安全

不要在日志、截图、Issue 或提交里输出 Cookie、账号文件内容、签名密码或密钥材料。
账号只由插件内的 `CredentialStore` 加密保存，主程序不读取 Cookie。

## 阅读与书架

- 番茄根页只展示真实分组；分组竖封面按 0.7 比例拼贴，采用 Mochi 同心圆角。分组→详情→阅读由同一导航器管理，侧滑返回逐级退出。
- 根底栏固定在 Mochi 同层转场之外，去过的根页保留组合与滚动状态。元信息按账号 scope 的 SHA-256 隔离落盘；分组12小时有效，手动刷新或数量变化时更新。缩略图内存上限3 MiB、磁盘20 MiB、下载并发2；不保存整页截图。
- 主页底栏“设置”和阅读页复用行距/背景控件，共用同一偏好，修改相互同步。
- 本地/在线阅读共用字体、字号、1.0–2.0行距、米白/纸黄/浅绿/夜间/跟随系统背景，以及关闭/淡入/轻滑动画设置。主界面、阅读操作栏、目录和设置、插件账号页沿用同一阅读配色与对应明暗文字。显示设置期间按进入时的字符偏移保留进度，不把格式调整当作阅读。
- 阅读默认沉浸，顶栏为时间、书名（章首页）或章节名、阅读进度；电池图标与电量位于右下。中间单击显示操作栏，目录使用半屏 MochiSheet；底栏拖动每44dp翻一页，可连续多页，无上一页/下一页按钮。
- 章节新页开始，放大标题下绘制分隔线；分页、绘制、页码映射使用同一套章节装饰和行距测量。文本索引按源文件大小/mtime复用，版式变化只更新页码布局缓存。

## 官方缓存导入

主页右上“导入”可选本地 TXT 或官方番茄缓存。后者只在设备已有 root 且授权时可用，无 root 直接放弃；不安装 root、不改官方 App 数据。
仅解析固定目录下已有缓存，匹配加密命名空间的 AES 密钥只留在进程内；不导出 Cookie、账号文件或密钥。导入以私有 TXT 原子落盘，不覆盖已有同名书。
缓存文件可能包含元数据、缺章和不可靠顺序，文件数量不等于章节数，导入不代表整书下载完成。已在小米官方5.5.3.99缓存上验证真实扫描、解码、导入及阅读；其它版本格式变化仍需实测。
