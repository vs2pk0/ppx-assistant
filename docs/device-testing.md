# 本地设备验证

测试工具仅用于 debug APK，需要连接已授权 root 的 ADB 设备。请只在自己的设备上运行。正式 APK 不含 `DeviceProbeHook` 和 `DeviceUiProbeHook`，不会轮询指令文件。

构建：

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon
```

安装 `app/build/outputs/apk/debug/app-debug.apk` 与 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。测试 APK 需要 `adb install -r -t`。需要启用助手并将皮皮虾加入 LSPosed 作用域。

开始前使用以下命令保存 `before`/`after` JSON 中的完整原配置，放到 `build/device/preferences-original.json`：

```sh
adb shell am instrument -w com.akari.ppx.test/com.akari.ppx.test.PreferenceInstrumentation
```

助手脚本默认使用 `ANDROID_HOME/platform-tools/adb`；未设置时使用 macOS 的 `~/Library/Android/sdk`。

```sh
python3 tools/device-test.py profile '{"__probe_ui":true,"__probe_models":true}'
adb shell am force-stop com.sup.android.superb
adb shell am start -n com.sup.android.superb/com.sup.android.base.MainActivity
python3 tools/device-test.py ui '{}'
```

模型检查依赖对应功能已经开启，日志为 `adb logcat -s PPXPPX` 中的 `DEVICE_TEST`。不是所有配置组合都会产生同一检查结果，例如关闭某个待测开关后，该项检查应当失败。正式交付以功能报告所列配置组合为准。

UI 指令通过皮皮虾私有 cache 文件传递，仅 root/宿主进程可写。支持按实际资源 ID 或完整文字定位；不要猜测 ID。`click`、`input` 会操作真实页面，可能触发发布或删除，执行前必须确认对象及授权。

```sh
python3 tools/device-test.py ui '{"action":"click","id":"实际界面ID"}'
python3 tools/device-test.py ui '{"action":"input","id":"实际输入框ID","value":"测试文本"}'
python3 tools/device-test.py ui '{"action":"clipboard"}'
```

专用检查：`media`（本地媒体轨道）、`sound`（播放一次短音效）、`self`（当前本地资料模型）、`sender`（用本人的 ID 构造弹幕模型，经真实 Hook 入口打开本人主页）。`sender` 验证的是模型和路由，不代表实际滚动弹幕手势的端到端验证。

恢复原配置后重启皮皮虾；再安装正式 APK。不要把测试开关留在正式用户配置中：

```sh
python3 tools/device-test.py restore build/device/preferences-original.json
adb shell am force-stop com.sup.android.superb
```

`iconTest` 调用实际隐藏图标功能并恢复原组件状态：

```sh
adb shell am instrument -w -e iconTest true com.akari.ppx.test/com.akari.ppx.test.PreferenceInstrumentation
```
