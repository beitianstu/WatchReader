# WatchReader

适用于安卓手表的 TXT 小说阅读器，支持表冠翻页、本地阅读和可选的番茄数据源插件。书架、在线小说和本地 TXT 共用一套阅读器。

## 功能

- 本地 TXT 书库、大文件阅读、目录跳转、字号调整和阅读进度保存。
- 触摸、滑动和表冠翻页；列表页面支持表冠滚动。
- 番茄插件：在线书架分类、小说搜索、详情、封面、连续跨章阅读和在线进度同步。
- 段评查看与发表，发表前需要手动确认。
- 下载到本地：默认 3 线程，可切换 1～4 线程；章节按序保存，支持暂停和断点续下。
- 下载完成后可以离线阅读；本地 TXT 进度目前不回传番茄云端。

## 安装与使用

系统要求：Android 10 / API 29 或更新版本。已在圆屏安卓手表上验证，其他设备的表冠行为可能不同。

从 [Releases](https://github.com/beitianstu/WatchReader/releases) 下载 APK：

1. 安装 `WatchReader-1.2.apk`，可以直接阅读本地 TXT。
2. 如需番茄功能，同时安装 `WatchReader-Fanqie-0.3.0.apk`。
3. 在主程序打开「番茄插件 · 在线书架 / 下载」，进入「插件账号设置」导入自己的 Cookie JSON 或粘贴 Cookie。
4. 在书籍菜单中阅读、查看目录或下载。下载完成的小说会加入主程序本地书库。

插件依赖主程序，没有独立的桌面入口。卸载主程序会清除其私有书籍、下载任务和进度；卸载插件会清除插件账号。升级前请自行备份重要文件。

## 阅读操作

| 操作 | 效果 |
| --- | --- |
| 表冠旋转 | 正文翻页，列表上下滚动 |
| 点正文左侧 / 右侧 | 上一页 / 下一页 |
| 左右滑动 | 翻页 |
| 长按正文或点击页码 | 阅读菜单、目录与设置 |
| 在线章节末尾继续翻页 | 自动进入下一章 |

## 开发构建

需要 Windows PowerShell、JDK 17、Android SDK Platform 33、Build Tools 34.0.0 和 Platform Tools，无需 Gradle。通过 `JAVA_HOME` 和 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）指定本机环境。

```powershell
# 生成主程序与插件
& .\fanqie\tools\build-apk.ps1 -HostApp
& .\fanqie\tools\build-apk.ps1

# 无设备进度逻辑回归
& .\fanqie\tools\test-progress.ps1

# 设备测试需要显式指定自己的目标；物理手表另加 -PhysicalWatch
& .\fanqie\tools\test-emulator.ps1 -Serial emulator-5554 -HostPlugin -ParallelOnly
```

APK 输出到 `fanqie/dist/`。主程序发布构建需要本地 `android/build/debug.keystore`（别名 `watchreader`）；插件构建使用本地 `fanqie/build/local-signing.keystore` 和同目录密码文件。私钥与密码不随源码提供。

自行构建时应使用自己的签名，并将 `fanqie/api/java/com/watchreader/fanqie/PluginTrust.java` 中的双方证书 SHA-256 更新为自己的值；主程序和插件需要配对构建。自签版本无法覆盖不同签名的发行版。证书指纹是公开信息，不能代替私钥。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `android/app/` | 主程序、本地书库及共享阅读器 |
| `fanqie/api/` | 插件协议与双向证书校验 |
| `fanqie/host/` | 在线界面、进度管理及并发下载 |
| `fanqie/android/` | 数据源接口、签名、解密及账号存储 |
| `fanqie/integration/`、`fanqie/plugin-tests/` | 联动及并发测试 |
| `tools/`、`fanqie/tools/` | 构建、安装及验证脚本 |

## 账号与数据

发行包不包含预置 Cookie、账号、阅读记录、设备标识、签名私钥或本地测试报告。Cookie 由使用者在设备上自行导入，加密保存在插件私有目录，不传给主程序；正文和下载文件保存在设备本地。请勿将 Cookie、私钥和调试日志提交到仓库。

仅使用自己的账号访问有权阅读的内容。番茄接口变化、账号权限或服务限流可能影响在线功能；下载离开页面或进程结束后不保证后台持续运行，可再次进入继续下载。单本下载上限为 128 MB。

当前版本完成了 110 项真机检查，包括并发、乱序保存、暂停续下、联动和离线回归；真实全本长篇下载及公开段评发表未自动验证。更多插件说明见 [fanqie/README.md](fanqie/README.md)。
