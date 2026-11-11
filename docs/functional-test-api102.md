# API 102 真机安装与调试记录

验证日期：2026-10-27。助手版本：0.0.11（20261026）。通过 USB 连接小米 13 Ultra，Android 16 / API 36，宿主皮皮虾 6.5.5（655）。框架为 LSPosed IT 2.1.0-it（7779），API 102，API 调用保护开启。

## 安装与运行结果

- 安装前备份原 APK 和助手配置，使用 `adb install -r` 覆盖安装最终 Release 包，然后强行停止并冷启动助手与宿主。
- 主进程及 `:push` 进程均记录 `Modern module loaded api=102`、`apiProtection=true`；两进程均为 `installed=55 failed=0 features=38`。
- 远程配置读取成功：`remote=ok(74 keys)`；快照修订号 `30860338`，`match=true`。
- 助手页面显示“框架已启用助手”和“同步正常（74 项设置）”。安装前后 DataStore 与 SharedPreferences 配置文件逐字节一致。
- 首页信息流正常显示；当前配置下顶部游戏入口、底部话题及消息入口隐藏，“我的”页正常打开。
- “我的”页的“皮友助手”入口可打开助手。分享面板显示“转发到发帖页”，运行日志确认 `ShareActions attached cell=ItemFeedCell`，随后关闭面板。
- 本轮最终包运行观察期间，采集日志无 `init failed` 或 `FATAL EXCEPTION`。

## 真机发现并修复的问题

初次启动有 1 项初始化失败：`CoinFloatBallHook` 引用了宿主不存在的 `com.sup.android.m_integral.money.MoneyExcitingUtil`。

核对手机实际 APK 的 `classes21.dex`：`MoneyExcitingService` 在添加金币视图后立即构造 `com.sup.android.m_integral.view.d(View, ViewGroup)`。现有构造 Hook 已能隐藏该视图，因而移除失效的重复 Hook，仅保留具体控制器的视图隐藏，避免拦截通用添加视图方法而影响其他界面。修复后两进程均为 0 项初始化失败。

## 构建验证与留档

- `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease` 通过；46 项单元测试，0 失败；Lint 0 错误、46 条警告；APK v2 签名验证通过。
- 最终 APK：`app/build/outputs/release-dist/皮友助手-v0.0.11-release.apk`。
- SHA-256：`d9541049c5b423593d1fc39ed75faec0a89501b8851aba078d71037f4477abff`。
- 本地备份、页面截图和日志：`build/device/api102-20261027-020512/`；修复后日志为 `fixed-logcat.txt`。设备日志使用手机本地时间。

本轮为安装、配置保留、启动及页面冒烟验证；未逐项执行下载、发布、评论发送等完整功能流程。
