# 启动展示页与母虾颜色验证

日期：2026-09-13。宿主：皮皮虾 6.5.0；模块包名：`io.github.vs2pk0.piyou`。

## 功能

- 广告与内容过滤：新增“跳过启动展示页”，默认关闭。通过首页的 SplashHelper 类型与方法签名定位，强制走宿主已有的无开屏分支，保留初始化、关闭回调和首页恢复逻辑。隐藏应用内启动背景及再次显示请求。
- 浏览与播放：新增“母虾提示颜色”，默认 `#FF6880`，与原有粉色整数值 -38784 一致。支持预设色、RGB 滑块、六位十六进制输入、预览、恢复默认、保存及取消。保存颜色不会自动开启母虾提示。
- 两项设置均在重启皮皮虾后生效。复用现有 DataStore、框架远程偏好与兼容镜像。

## 验证

- 20 项单元测试通过，包含原有设置分类完整性、新颜色格式校验、透明及非法颜色回退、原默认色兼容。
- 6.5.0 真机分别以蓝色 `#1976D2` 和默认粉色冷启动，两轮均通过四项调试探针：强制有开屏输入走无开屏分支；首页开屏状态结束；启动图显示请求被抑制；女性用户名颜色准确且复用到其他用户名后无残留。
- 关闭跳过开屏后冷启动正常，Hook 数由 40 回到 39，无初始化失败。
- 已静态核对 6.2.0 的 SplashHelper 方法签名及无开屏流程一致；未降级手机重新进行 6.2.0 真机验证。
- 调试与正式构建、Lint 通过。调试探针仅进入 debug APK，正式 APK 不包含探针。

## 边界

此功能不会跳过宿主初始化或加快网络加载，仍可能有短暂白屏。Android 12 以下仅隐藏应用内启动图；系统启动主题接口从 Android 12 开始提供。当前尚未完成颜色对话框全部触控、横屏和大字体视觉回归。

本地证据：`build/device/splash-color-blue.log`、`splash-color-pink.log`、`splash-disabled.log`、`splash-color-build-final.log`。

## 首屏未隐藏的后续修复

2026-09-13 在 Android 16 / 皮皮虾 6.5.0 上录屏复现：上述三个应用内开屏探针通过时，卡通启动图仍在显示。根因是系统在宿主进程启动前，使用宿主主题中的 windowBackground 绘制 starting window；Activity.onCreate 修改背景无法改变这层窗口。之前的探针没有覆盖系统窗口，不能作为启动图消失的证据。

新增 StartingWindowHook，在首页 onResume 使用 SplashScreen.setSplashScreenTheme 持久化系统可解析的 android:style/Theme.Light.NoTitleBar。保留原 SplashHook 处理应用内开屏。独立恢复 Hook 在开关关闭时仍运行，并用 0 清除本模块设置的启动主题覆盖；只在本模块曾设置覆盖时执行恢复。

- 开关变更后先进入一次宿主，下次冷启动生效，已同步更新设置说明。
- 开启后录屏：卡通人物及底部品牌图消失，白屏后进入首页。
- 关闭后先启动一次，再冷启动录屏：原卡通启动图恢复，首页正常加载。
- 测试结束恢复 skip_splash=true，其他用户配置不变。
- 正式 APK 覆盖安装后再次冷启动录屏，卡通图仍未出现，首页正常加载，模块报告加载成功。20 项单元测试通过；Release 构建和 Lint 通过（0 errors，29 warnings）。测试辅助 APK 已卸载。
- 系统主题是持久化设置。停用或卸载模块前，如需恢复原图，应先关闭开关并打开一次皮皮虾；直接停用模块将无法执行恢复 Hook。

对比录像及每秒四帧联系表保存在 `build/device/piyou-splash-before.*`、`piyou-splash-enabled.*`、`piyou-splash-disabled.*`、`piyou-splash-release.*`，日志为 `build/device/splash-system-final.log`。未在 6.2.0 或 Android 12 以下设备进行本轮真机验证。

## 透明背景试验

同日按用户要求，将持久化启动主题临时换成 android:style/Theme.Translucent.NoTitleBar，构建并安装正式包，先启动一次应用更新主题，再录制下一次冷启动。卡通人物和皮皮虾品牌图重新出现，没有实现桌面透明过渡。结果符合 Android ActivityRecord.evaluateStartingWindowTheme / validateStartingWindowTheme 的行为：透明替代主题校验失败时保留原主题。

已撤回透明主题试验，恢复 Theme.Light.NoTitleBar。录像及每秒四帧联系表：`build/device/piyou-splash-transparent.*`。仅将 Activity 窗口背景改为透明无法改变进程启动前的系统窗口，因此未将这一无效做法加入功能。
