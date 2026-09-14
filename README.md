# 皮友助手

一个基于 LSPosed 的皮皮虾增强模块。

应用包名：`io.github.vs2pk0.piyou`。从 `0.0.3` 起使用与 LSPosed 官方收录仓库一致的包名；旧包 `com.akari.ppx` 和 `com.vs2pk0.piyou` 的设置不会自动迁移。安装后请在 LSPosed 中停用旧模块、启用新安装的皮友助手并勾选皮皮虾作用域，再重启皮皮虾。

当前维护：[我的馬眼会发光 / vs2pk0](https://github.com/vs2pk0) · [项目仓库](https://github.com/vs2pk0/ppx-assistant) · [问题反馈](https://github.com/vs2pk0/ppx-assistant/issues)

## 设置与支持

设置已按下载保存、广告过滤、评论发布、浏览播放、界面资料分类，支持全局搜索。
自动操作尚未可靠适配，已隐藏整组设置并停用对应执行入口；历史自动开关不再触发操作。
关于页列出适配版本（皮皮虾 6.2.0、6.5.0），提供 [酷安主页](https://www.coolapk.com/u/270261) 和赞赏入口。
支付宝可跳转收款金额输入页；微信请保存收款码后打开扫一扫，从相册识别。赞赏金额由用户在支付应用中填写并确认。

分享菜单新增“转发到发帖页”，可带入原文字、全部图片或视频，进入编辑页后由用户确认发布。
纯文字楼中楼可长按回复，选择“分享”→“转发到发帖页”。修复详情分享中“复制文字”闪退。
本轮验证范围与限制见 [转发功能验证记录](docs/forward-test-6.5.0.md)。

## 项目说明

本仓库是基于原项目 [Secack/ppx](https://github.com/Secack/ppx) 的重构与后续维护版本。

本次整理保留了原项目的整体思路，并在此基础上补充了：

- 现代 `libxposed` 入口与运行时桥接
- `LSPosed 1.9.x` legacy 与 `LSPosed 2.0` modern 双线兼容
- 新版本皮皮虾适配与一轮功能修复
- 更清晰的文档与构建产物归档

感谢原作者 `Secack / Akari` 提供原始项目与设计基础。

## 兼容范围

- 宿主适配目标：`皮皮虾 6.2.0 / 6.5.0`
- 框架兼容：
  - `LSPosed 1.9.x` -> legacy Xposed API 93
  - `LSPosed 2.0` -> modern libxposed API 101

## 26.09.13-r6

完成皮皮虾 `6.5.0 (650)` 回归，首页与关于页统一显示 `6.5.0 / 6.2.0` 适配列表。17 项单元测试、50 项真实宿主模型检查通过；真机验证覆盖视频、音频、图片保存、红色评论、消息 99+、倍速、资料显示和正式冷启动。已测范围未发现需要修改的 Hook，自动操作继续隐藏停用。

完整实测范围、尚未重复验证的场景和交付记录见 [6.5.0 验证报告](docs/functional-test-6.5.0.md)。

## 项目结构

- `app/`：主模块源码
- `xposed-modern-api101-entry/`：modern API 101 入口
- `buildSrc/`：Gradle 版本与构建辅助
- `tools/`：构建镜像同步脚本

## 构建

需要 JDK 17、Android SDK 36，并在本机 `local.properties` 中配置 `sdk.dir`。

macOS / Linux：

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon
```

Windows：

```bash
./gradlew.bat :app:assembleRelease --no-daemon
```

如果先在源码目录开发，再在镜像目录构建，先执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\sync-build-mirror.ps1
```

Release APK 输出：

- `app/build/outputs/release-dist/`

## 2026-09-12 适配修复

目标设备安装的是皮皮虾 `6.2.0 (620)`、Android 16、SukiSU Ultra，LSPosed 模块文件版本为 `2.1.1 (7790)`。运行日志报告框架 API 102，使用本项目的 API 101 入口加载。

- 评论响应类通过字段特征动态定位，避免使用反编译器从 Kotlin 元数据还原的类名（该 APK 的实际类名为 `com.sup.android.mi.feed.repo.response.a`）。
- 评论广告按模型类型识别，修复 `getAdInfo()` 被置空后广告漏判；支持广告子类和 Docker 包装数据。
- 覆盖评论适配器的两条列表追加入口，过滤时创建新列表，避免修改只读输入。
- 助手界面处理系统栏安全区域和 Scaffold 内容间距，适配 Android 16。
- 现代框架通过远程配置同步开关；旧版公开配置不可用时使用私有本地镜像，避免反复抛出权限异常。

扩展适配还修复了评论/帖子正则异常、空头像挂饰、频道改名匹配、复制评论/回复、新 CDN 视频地址、消息 99+、新页脚、点赞音效资源路径、本地表情导入上限、资料匹配和普通松手事件。版本号更新为 `26.09.12`。

10 项本地回归测试与 44 项真实宿主模型检查通过。真机验证覆盖下载/音频/复制、测试评论、时间、频道、双列布局、隐藏栏位、自定义资料、99+、新页脚、倍速、音效、搜索、地点选择和弹幕发送人路由。完整逐项结果和未覆盖的服务端场景见 [功能测试记录](docs/functional-test-6.2.0.md)。测试评论已删除，未发布新帖子。

调试验证方法见 [设备测试说明](docs/device-testing.md)。调试指令通道仅存在于 debug APK，正式 APK 不包含它。

## 下载

已构建版本通过 [GitHub Releases 下载页](https://github.com/vs2pk0/ppx-assistant/releases) 和 [LSPosed 收录仓库](https://github.com/Xposed-Modules-Repo/io.github.vs2pk0.piyou/releases) 分发。当前版本为 `0.0.3`，使用 `0.0.x` 命名，内部版本号保持递增。`0.0.1` 构建在应用更名时取消，未发布安装包。

发布时先更新 `app/build.gradle.kts` 中的版本名和版本号，再添加 `docs/releases/<版本>.md` 更新说明。推送对应的 `v<版本>` 标签后，Actions 自动运行测试、lint 和签名构建，并发布带中文更新说明的 Release、APK 和 SHA-256 校验文件；Actions 摘要也会显示更新内容。手动运行工作流时填写已存在的版本标签。

CI 签名使用仓库 Secrets：`PPX_KEYSTORE_BASE64`、`PPX_STORE_PASSWORD`、`PPX_KEY_ALIAS`、`PPX_KEY_PASSWORD`，缺少密钥时停止发布。当前沿用既有安装包签名以支持覆盖升级，签名材料不入库。

## 许可

本项目沿用原项目许可证：

- [GNU General Public License v3.0](LICENSE)

### 26.09.12-r2

增加按功能状态显示的“去水印保存”、蓝/红评论颜色、每次冷启动加载提示；修复消息99+与去红点冲突，补齐晚加载与配置同步兜底。设置改为分区卡片和全局搜索，更新助手图标。

红色评论在本模块中渲染，对方显示效果取决于其客户端支持。楼中楼视频已完成实际发送与播放验证。详见 [本轮测试记录](docs/functional-test-6.2.0-r2.md)。

## 26.09.12-r3

评论工具栏新增“虾”快捷设置，颜色修改无需重启；图片/GIF 查看页新增“存”按钮，原始内容按真实格式保存到相册。关于页已迁移到本仓库维护者信息。

见 [r3 验证记录](docs/functional-test-6.2.0-r3.md)。
