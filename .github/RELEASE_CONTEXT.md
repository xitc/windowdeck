### 适配与安装

- 当前适配基线：一加 13，ColorOS 17 / C17，`PJZ110_17.0.0.100(SP01CN01)`，桌面 17.3.9。
- 不兼容旧 C.93 / Android 16 基线；其他设备及 OTA 后兼容性尚未验证。
- 需要 Root 与 LSPosed。启用「多窗工作台」，作用域选择 `com.oplus.pscanvas` 和 `com.android.launcher`，授权 Root 后重启这两个进程。
- 包名由 `dev.windowdeck.app` 迁移到 `io.github.xitc.windowdeck`。新版独立安装，不能覆盖旧包；先退出旧工作台、停用旧模块，再安装新版并重新授予 Root、启用 LSPosed 和勾选作用域；配置不自动迁移。

### 已知限制

- 项目依赖 ColorOS 私有接口，系统更新后可能失效。
- 动画衔接、复杂输入法、锁屏、进程恢复和长期负载仍需真机验证。
- 每日版可能包含尚未验收的改动，具体真机验证记录以对应版本的更新记录为准。
