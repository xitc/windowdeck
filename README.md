# 多窗工作台 · WindowDeck

**当前版本：v0.4.8-beta.27 · Beta 测试版**

面向 **C17（ColorOS 17 / Android 17）** 的 LSPosed 多应用工作台，基于系统实时任务嵌入能力，让最多五个应用同时出现在主窗和侧窗中。应用包名：`dev.windowdeck.app`。

**目前优先支持 C17**：基线为一加 13 `PJZ110_17.0.0.100(SP01CN01)`（C17 = F.04）、Android 17、桌面 17.3.9。旧基线 C.93（Android 16）已不再适配，装上不会生效。

这是实验性项目，依赖 ColorOS 私有接口，尚不是稳定版，也不是 vivo 原子工作台的官方移植。

## 下载

从 [GitHub Releases](https://github.com/xitc/windowdeck/releases) 下载最新 Pre-release。当前源码版本为 `windowdeck-v0.4.8-beta.27`。

## 环境与验收范围

- 验证设备：一加 13（PJZ110），`PJZ110_17.0.0.100(SP01CN01)`（C17 = F.04），Android 17，桌面 17.3.9，KernelSU + LSPosed。
- 当前代码最低 API 35；不表示已经验证 Android 15、其他设备或其他 ROM。
- 0.4.4-beta.2 与 0.4.5-beta.1 曾在旧基线完成真机模块验收；0.4.8-beta.27 起在 C17 上做过冷启动、追加主窗与五窗容量的真机确认。结果只代表这一固件和已测应用，不能视为完整兼容保证。

## 安装

1. 安装 APK，在 LSPosed 启用“多窗工作台”。
2. 作用域选择“多窗口” `com.oplus.pscanvas` 和“系统桌面” `com.android.launcher`。
3. 在 Root 管理器中授权“多窗工作台”。
4. 重新启动 `com.oplus.pscanvas` 和系统桌面，使两个 Hook 重新加载。
5. 打开应用，选择两个到五个不同应用。恢复已有组合用“打开 / 恢复当前工作台”，重建组合用“用所选应用新建工作台”。

如果安装过旧包名实验版，请先在 LSPosed 停用旧模块，再启用新版并重启宿主进程。新包名会独立安装，不覆盖旧应用，也不自动迁移应用选择、Root 授权和 LSPosed 配置。

启动会替换当前多窗口容器。当前仅适配主用户，不支持工作资料或多用户。

## 功能

- 最多五个实时应用任务，支持主窗与侧窗切换。
- 运行中添加、替换、移出应用，支持左右与上下排列。
- 点击侧窗切换为主应用；长按打开管理菜单。
- 重复选择已经在工作台里的应用时，该应用切到主窗，原主窗回到它原来的侧卡位置，其余侧卡不动；已经是主窗时提示已在前台启动，不重复添加。
- 侧窗滑动移出、短滑回弹，以及一个应用的本地固定保护。
- 切换动画使用固定 Surface 尺寸；移出恢复期间使用临时任务快照覆盖。
- 竖握时横屏任务旋转 90° 显示为长卡片，横握时恢复正向。
- 系统返回优先交给主应用处理键盘、弹窗和内部页面；根页面返回桌面并保留当前组合。
- 竖屏和横屏上滑的分屏/浮窗面板在中间增加“添加到工作台”，样式与系统分屏、浮窗选项一致。有工作台时追加当前任务，没有时新建。出现跟手，选中使用与系统一致的临界阻尼弹簧。
- 侧边「＋」和主窗「替换应用」都先回到桌面再选择。添加会把新应用放到主窗；替换只换主窗。

入口页的「侧窗梯形：开 / 关」默认关闭。打开后，左右与上下布局的侧窗都收成梯形：朝主窗的那条边缩短，外侧保持满高；主窗永远是矩形。

点击 `•••` 打开工作台菜单。手机横握时使用左右布局。左右布局为左侧预览栏、右侧主窗；上下布局为顶部预览、下方主窗。

## 已知限制

- 固定保护只覆盖工作台内的替换、移出和滑动操作，不拦截应用内部启动其他应用。
- 当前所有主应用根页面都会回桌面；尚未按来源任务、新 Activity、新任务和跨应用跳转复刻全部原版返回语义。
- 运行时方向变化、复杂输入法、多指、锁屏、进程死亡恢复及长时间负载仍需进一步验证。
- 无法获取任务快照时使用中性底色，受保护应用与高负载恢复仍需专项测试。
- 游戏验证主要覆盖启动画面和基础触控，不代表所有游戏实战场景通过。
- 上下布局的侧窗梯形收幅约 1.3 dp，视觉上不明显。
- 本版只适配 C17 的桌面混淆符号。OTA 之后混淆名可能再次变化：升级前可以先用 `python3 tools/rom_contract_audit.py --dex <dex 清单>` 审计，任一项对不上模块会整块跳过安装，而不是崩溃。
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

输出：`build/windowdeck/windowdeck-v0.4.8-beta.27.apk`。可用 `BUILD_TOOLS_VERSION` 覆盖 Build Tools 版本。脚本也会尝试发现本地 Gradle 缓存中的 Xposed API 82。

`tools/rom_contract_audit.py` 用于在安装前核对 C17 桌面的混淆符号表。它需要一份 `apkanalyzer dex packages --defined-only` 的输出文件，用 `--dex` 指定或设置 `LAUNCHER_DEX_DUMP`。

本地构建使用 `build/windowdeck-test.keystore`。文件不存在时脚本会生成一把新密钥；密钥和构建产物已从 Git 排除。请保留自己的密钥，否则下一版不能覆盖安装。

GitHub Actions 每天北京时间 **04:17** 检查 `main`，发布有变化的每日测试版；推送本身不触发发布。GitHub 定时调度可能延迟，并不保证在 04:17 准时完成。工作流必须在默认分支 `main` 上才会按时触发；公开仓库连续 60 天没有活动时，GitHub 可能停用定时工作流，需要重新启用。

- 对比上次成功发布的源码、测试、构建配置和更新记录；内容不变或仅修改 README 时，在安装 SDK 前跳过。检查及构建失败时不推进成功发布基线，下次继续重试。
- 每日版的 APK 版本例如 `0.4.8-nightly.20261006.abcdef123456`，包含北京时间日期与源码提交；标为 Pre-release，标题为「WindowDeck 2026-10-06 · 每日测试版」，不设为稳定版 Latest。
- `versionCode` 按已有发布与草稿的最大值递增。版本号只改 Actions 工作区中的 manifest，不向仓库提交版本变更；每日版和手动命名的版本共享递增规则，手动 Beta 也能覆盖已安装的每日版。
- 使用 Secret `WINDOWDECK_KEYSTORE_BASE64` 里的原发布密钥签名，并校验原发布证书。自己生成的密钥与 GitHub 发布版不同，不能覆盖安装。
- Release 附带 APK、`SHA256SUMS` 和 `BUILD_INFO.json`。先上传到草稿，附件齐全后才公开；失败留下的草稿可重试，已有公开版本不覆盖。
- 说明按上次发布以来的提交归类，附适配、安装、已知限制、自动验证状态、源码与构建链接。建议提交使用 `feat: 新增功能`、`fix: 修复问题`、`perf:`、`refactor:`、`ci:`、`build:` 或 `test:`，其余归入「其他变更」。自动分类依赖提交说明，不会自动推断功能效果或真机通过状态。

需要立即检查发布时，在 Actions → Release → Run workflow 选择 `main`，保留默认 `channel=nightly`。需要发布手动命名的 Beta/正式版时，先修改 manifest 的 `versionName`，在 `CHANGELOG.md` 写好 `## v<versionName>`，再选择 `channel=version`；版本名采用 manifest，`versionCode` 自动保持递增。已有同名公开 Release 时在编译前跳过。

`.github/RELEASE_CONTEXT.md` 维护 Release 的共同适配要求、安装步骤与已知限制；适配基线变化时应同步更新。每日自动构建只做发布流程回归、布局回归、APK 编译及验签，真机验收需另行记录。发布流程回归可用 `python3 -m unittest discover -s tests -p 'test_release.py' -v` 在本地运行。

当前工具链存在 min-api 35 的编译器支持警告，构建和签名检查通过，后续仍需统一工具链。

## 实现边界

Hook 仅在 `com.oplus.pscanvas` 中对携带本模块标记的容器启动生效。实际任务嵌入复用 ColorOS 的 `FlexibleTaskView`，不修改系统分区、签名检查或 `system_server`。

桌面侧的混淆名只允许出现在 `module/src/dev/windowdeck/app/RomSymbols.java` 一处，主逻辑只引用常量。安装前 `RomSymbols.validate()` 会逐项校验，任何一项对不上就整块跳过安装，不在 `handleLoadPackage` 里抛异常。

源码在 `module/`，纯 Java 回归测试在 `tests/`，构建脚本在 `tools/`。仓库不包含本机截图、设备日志、任务快照或签名私钥。

## 反馈

请通过 Issues 提供版本、设备型号、系统版本、LSPosed 版本及复现步骤。分享日志或截图前，请移除账号、通知和其他个人信息。

## 停用

先从工作台菜单退出，在 LSPosed 停用模块，重新启动 `com.oplus.pscanvas` 后卸载 `dev.windowdeck.app`，并撤销 Root 授权。无需清除原应用数据。
