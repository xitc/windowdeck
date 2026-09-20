# 多窗工作台 · WindowDeck

**当前版本：v0.4.3-beta.1 · Beta 测试版**

面向 ColorOS 16 的 LSPosed 多应用工作台，基于系统实时任务嵌入能力，让最多三个应用同时出现在主窗和侧窗中。应用包名：`dev.windowdeck.app`。

这是实验性项目，依赖 ColorOS 私有接口，尚不是稳定版，也不是 vivo 原子工作台的官方移植。

## 下载

从 [GitHub Releases](https://github.com/xitc/windowdeck/releases) 下载 `windowdeck-v0.4.3-beta.1.apk`。本版在 GitHub 标记为 **Pre-release**。

## 环境与验收范围

- 既有功能验证设备：一加 13（PJZ110），ColorOS `16.0.10.501(CN01)`，Android 16，KernelSU + LSPosed。
- 当前代码最低 API 35；不表示已经验证 Android 15、其他设备或其他 ROM。
- 新包名 Beta 已通过本地编译、签名校验、布局与手势回归测试，**尚未完成新包名下的真机模块验收**。
- 之前的真机结果来自旧包名 0.4.3 及更早版本，不能视为对当前发布包的完整兼容保证。

## 安装

1. 安装 APK，在 LSPosed 启用“多窗工作台”。
2. 作用域仅选择“多窗口” `com.oplus.pscanvas`。
3. 在 Root 管理器中授权“多窗工作台”。
4. 重新启动 `com.oplus.pscanvas` 进程，使模块加载。
5. 打开应用，选择两个或三个不同应用，点击“启动工作台”。

如果安装过旧包名实验版，请先在 LSPosed 停用旧模块，再启用新版并重启宿主进程。新包名会独立安装，不覆盖旧应用，也不自动迁移应用选择、Root 授权和 LSPosed 配置。

启动会替换当前多窗口容器。当前仅适配主用户，不支持工作资料或多用户。

## 功能

- 最多三个实时应用任务，支持主窗与侧窗切换。
- 运行中添加、替换、移出应用，支持左右与上下排列。
- 点击侧窗切换为主应用；长按打开管理菜单。
- 侧窗滑动移出、短滑回弹，以及一个应用的本地固定保护。
- 切换动画使用固定 Surface 尺寸；移出恢复期间使用临时任务快照覆盖。
- 根据应用声明区分横屏与竖屏渲染尺寸。

点击 `•••` 打开工作台菜单。手机横握时使用左右布局。**本版没有新增横屏模式切换开关；竖屏工作台内的横屏应用仍会等比显示并保留上下空白。**

## 已知限制

- 固定保护只覆盖工作台内的替换、移出和滑动操作，不拦截应用内部启动其他应用。
- 运行时方向变化、复杂输入法、多指、锁屏、进程死亡恢复及长时间负载仍需进一步验证。
- 无法获取任务快照时使用中性底色，受保护应用与高负载恢复仍需专项测试。
- 游戏验证主要覆盖启动画面，不代表所有游戏实战与触控场景通过。
- OTA 后私有接口可能变化；其他 ROM 不在当前支持范围内。

## 构建与测试

依赖 JDK（支持 `javac --release 8`）、Android SDK Platform 35、Build Tools 35.0.0、Xposed API 82 JAR，以及 `zip`。本仓库不分发 Android SDK 或 Xposed 依赖 JAR。

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
export XPOSED_API=/path/to/api-82.jar
export PATH="$JAVA_HOME/bin:$PATH"
sh tools/test_layout.sh
sh tools/build_module.sh
```

输出：`build/windowdeck/windowdeck-v0.4.3-beta.1.apk`。可用 `BUILD_TOOLS_VERSION` 覆盖 Build Tools 版本。脚本也会尝试发现本地 Gradle 缓存中的 Xposed API 82。

构建使用本地开发签名 `build/windowdeck-test.keystore`，首次构建时生成。请妥善保留自己的密钥以便覆盖升级；密钥和构建产物已从 Git 排除。自行构建的签名与 GitHub 下载版不同，不能直接覆盖安装。当前工具链存在 min-api 35 的编译器支持警告，构建和签名检查通过，后续仍需统一工具链。

## 实现边界

Hook 仅在 `com.oplus.pscanvas` 中对携带本模块标记的容器启动生效。实际任务嵌入复用 ColorOS 的 `FlexibleTaskView`，不修改系统分区、签名检查或 `system_server`。

源码在 `module/`，纯 Java 回归测试在 `tests/`，构建脚本在 `tools/`。仓库不包含本机截图、设备日志、任务快照或签名私钥。

## 反馈

请通过 Issues 提供版本、设备型号、系统版本、LSPosed 版本及复现步骤。分享日志或截图前，请移除账号、通知和其他个人信息。

## 停用

先从工作台菜单退出，在 LSPosed 停用模块，重新启动 `com.oplus.pscanvas` 后卸载 `dev.windowdeck.app`，并撤销 Root 授权。无需清除原应用数据。
