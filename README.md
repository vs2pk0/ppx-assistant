# 皮皮虾助手

一个基于 LSPosed 的皮皮虾增强模块。

当前维护：[我的馬眼会发光 / vs2pk0](https://github.com/vs2pk0) · [项目仓库](https://github.com/vs2pk0/ppx-assistant) · [问题反馈](https://github.com/vs2pk0/ppx-assistant/issues)

## 设置与支持

设置已按下载保存、广告过滤、评论发布、浏览播放、界面资料分类，支持全局搜索。
自动操作尚未可靠适配 6.2.0，已隐藏整组设置并停用对应执行入口；历史自动开关不再触发操作。
关于页列出适配版本（目前仅支持皮皮虾 6.2.0），提供 [酷安主页](https://www.coolapk.com/u/270261) 和赞赏入口。
支付宝可跳转收款金额输入页；微信请保存收款码后打开扫一扫，从相册识别。赞赏金额由用户在支付应用中填写并确认。

## 项目说明

本仓库是基于原项目 [Secack/ppx](https://github.com/Secack/ppx) 的重构与后续维护版本。

本次整理保留了原项目的整体思路，并在此基础上补充了：

- 现代 `libxposed` 入口与运行时桥接
- `LSPosed 1.9.x` legacy 与 `LSPosed 2.0` modern 双线兼容
- 新版本皮皮虾适配与一轮功能修复
- 更清晰的文档与构建产物归档

感谢原作者 `Secack / Akari` 提供原始项目与设计基础。

## 兼容范围

- 宿主适配目标：`皮皮虾 6.2.0`
- 框架兼容：
  - `LSPosed 1.9.x` -> legacy Xposed API 93
  - `LSPosed 2.0` -> modern libxposed API 101

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

已构建版本通过 [GitHub Releases 下载页](https://github.com/2186056836/ppx/releases) 分发。

## 许可

本项目沿用原项目许可证：

- [GNU General Public License v3.0](LICENSE)

### 26.09.12-r2

增加按功能状态显示的“去水印保存”、蓝/红评论颜色、每次冷启动加载提示；修复消息99+与去红点冲突，补齐晚加载与配置同步兜底。设置改为分区卡片和全局搜索，更新助手图标。

红色评论在本模块中渲染，对方显示效果取决于其客户端支持。楼中楼视频已完成实际发送与播放验证。详见 [本轮测试记录](docs/functional-test-6.2.0-r2.md)。

## 26.09.12-r3

评论工具栏新增“虾”快捷设置，颜色修改无需重启；图片/GIF 查看页新增“存”按钮，原始内容按真实格式保存到相册。关于页已迁移到本仓库维护者信息。

见 [r3 验证记录](docs/functional-test-6.2.0-r3.md)。
