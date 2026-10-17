# WatchReader 1.2 + 番茄数据源插件 0.3.0

番茄功能现在是主项目的插件，不再作为独立阅读 App。

- **WatchReader 主程序**：本地书库、在线书架界面、分类、搜索、书籍详情和封面、段评界面、统一阅读器、连续跨章、准确页码、进度管理、本地下载和离线阅读。
- **番茄插件**：账号加密存储、番茄接口、签名、正文解密、进度与段评的数据读写。没有独立书架、阅读器或桌面启动入口，只接受配套主程序调用。
- 原包名与签名均保留：主程序 `com.watchreader`；插件 `com.watchreader.fanqie`。升级无需卸载，原插件账号可继续使用，旧版有效阅读进度会导入主程序。原主程序已有的本地书籍和进度保留。

## 使用

1. 安装 `WatchReader-1.2.apk` 和 `WatchReader-Fanqie-0.3.0.apk`，两者需要配套使用。Android 10/API29 起。
2. 打开主程序 → **番茄插件 · 在线书架 / 下载**。首次自动打开插件连接页建立联动，无需修改系统安全设置。
3. 账号 → **插件账号设置**：在插件中导入下载器的 Cookie JSON 或粘贴 Cookie。账号不会传给主程序，也不会打包进 APK。
4. 选择书架分类或搜索小说，点书名进入二级菜单：开始/继续阅读、目录、详情、封面、**下载到本地**。
5. 书架、分类、搜索结果、目录、段评/段落、详情及账号设置页支持表冠上下滚动，按钮获得焦点时也可操作；正文仍使用表冠翻页。
6. 在线阅读与本地 TXT 共用主项目的 ReaderView / Paginator / 表冠和字号设置。章末向后翻自动进入下一章，章首倒翻回上一章末页。总页数按当前章节实际排版计算，未完成时显示省略号。
7. **下载到本地 → 开始 / 继续下载**：默认3线程并发，可点击“下载线程”切换1～4线程；并发获取后按目录顺序保存，支持暂停和断点续下。完成后加入主程序本地书库，也可直接点击“离线阅读”。不完整内容不混入已完成书库。
8. 下载好的 TXT 属于主程序，即使断网或停用/移除番茄插件也可离线阅读。下载过程中退出或进程被系统结束，下次进入同一本书继续下载；没有承诺后台持续运行。单本上限128MB，空间不足或章节无权限会暂停并保留已完成部分。
9. 长按正文或点击页码打开阅读菜单，选择段落可查看段评；发表需要预览后确认，不会自动公开发送。在线阅读继续同步番茄进度；下载TXT的离线阅读沿用主程序本地进度，目前不回传番茄云端。

安装器不会替你卸载应用或清除数据。主程序卸载会清除其私有本地书籍、下载任务和进度；插件卸载会清除插件账号。发行 APK 通过项目 Releases 提供。

## 构建与验证

```powershell
& .\fanqie\tools\build-apk.ps1 -HostApp -Debug # 主程序调试包
& .\fanqie\tools\build-apk.ps1 -Debug          # 数据源插件调试包
& .\fanqie\tools\test-emulator.ps1 -Serial '<device-serial>' -PhysicalWatch -HostPlugin
& .\fanqie\tools\test-emulator.ps1 -Serial '<device-serial>' -PhysicalWatch -HostPlugin -CrownOnly
& .\fanqie\tools\test-emulator.ps1 -Serial '<device-serial>' -PhysicalWatch -HostPlugin -ParallelOnly
& .\fanqie\tools\test-emulator.ps1 -Serial '<device-serial>' -PhysicalWatch -NativeParallelOnly
& .\fanqie\tools\test-offline-plugin.ps1        # 暂停插件后验证离线，再恢复原状态
& .\fanqie\tools\test-progress.ps1             # 19项进度逻辑测试
& .\fanqie\tools\build-apk.ps1 -HostApp         # 主程序发布包
& .\fanqie\tools\build-apk.ps1                  # 数据源插件发布包
```

主程序继续使用项目原签名，插件继续使用0.1.x本地签名，两份私钥均不进入发布包。跨应用接口校验双方已配对证书，读取大目录/章节通过文件描述符管道，避免 Binder 大小限制；控制请求与章节下载分开排队，插件最多4个章节工作线程；主程序持有连接期间维持插件关联。Cookie仅由插件发送到固定番茄域名。封面为主程序独立访问公开 HTTPS CDN，不携带账号。

当前版本通过110项真机检查，包括并发下载、密钥会话隔离、主程序联动和离线阅读。本地原始报告不随源码与发行包公开。真实公开段评及真实全本长篇下载未自动验证。

开发环境、签名配对和发行下载见[项目 README](../README.md)。
