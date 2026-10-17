# 主程序 + 数据源插件

- `api/java`：ReaderPlugin、版本化数据协议 PluginWire、双向签名校验 PluginTrust。
- `host/java`：主程序中的番茄界面、统一阅读器接入、进度管理、公开封面、本地断点下载；与 `android/app/java` 一起编译进 com.watchreader。
- `android/java`：仅数据源 FanqiePlugin、DesktopSigner、CredentialStore、受保护 FanqieProvider 和账号设置 PluginSetupActivity，编译进 com.watchreader.fanqie。
- 插件没有 ReaderView / Paginator / ReaderActivity，也无桌面启动器。主程序没有签名算法、内容密钥协商或 Cookie 读取代码。
- 旧包名和原签名保持不变，以覆盖升级保留账号/本地书籍/进度。插件旧进度只通过受保护数据接口迁移，Cookie和设备标识不跨应用导出。

IPC使用content URI v1 + ParcelFileDescriptor管道，单请求长度和响应大小受限，控制请求使用单独串行队列，章节请求使用最多4线程的独立队列；各工作线程持有独立章节会话，共享初始化后的身份/密钥快照，避免密钥刷新状态并发冲突；入口校验主程序UID及证书，主程序也校验提供者包名和证书。
华为真机iAware会拦截冷启动跨应用ContentProvider。主程序通过用户发起的正常显式Activity结果流程连接插件，再持有ContentProviderClient；没有修改系统关联启动或安全策略。

LocalBookDownloader只写主程序私有目录。每章落盘并同步后，用AtomicFile提交已完成章数、文本偏移和目录前缀指纹；恢复时截断未提交尾部，目录已完成前缀变动则重新构建临时文件。完结后原子移动为书库TXT，残留临时文件不显示在书库。暂停、故障、进程退出均保留检查点。
在线阅读仍按需预取下一章，内存最多8章；离线TXT使用原ReaderActivity，字号统一使用主程序watchreader.fontSize配置。共享ReaderView的章节边界、末页定位、后台准确计页保持一套。


0.2.1：api/java新增CrownScroller，由主程序列表Activity和数据源账号设置Activity共同使用。它仅处理可见ScrollView的AXIS_SCROLL；正文绕过此处理器，继续由ReaderView响应表冠。

0.3.0：LocalBookDownloader采用进程内最多4线程的有界取章线程池，单任务只预取所选线程数的窗口，协调线程按章节序号消费结果、落盘并提交检查点。暂停/失败取消剩余Future，不等待网络超时才返回；未提交结果不写文件。已落盘的连续章节恢复时不会再次抓取。下载页默认3，可切换1～4并持久保存。插件控制接口与章节队列分离，初次协商密钥只做一次；后续每个工作线程的密钥刷新状态相互隔离，hello切换账号时清理会话缓存。
