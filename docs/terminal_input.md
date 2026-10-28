# 终端显示与键盘输入

本地可见终端使用不占字符宽度的 Bash 提示符，不再注入 ASCII 标志、欢迎文字和 `root@localhost:~#`。首次就绪仅清理初始化画面，输入从左上角开始；已有终端会话在重新创建后使用新设置。

## Shell 与状态协议

`TerminalManager` 在 Ubuntu 环境的 `/etc/operit/terminal.bashrc` 写入应用管理的交互配置。配置先读取 `/etc/profile`，再按 Bash 原有顺序读取首个可读的 `.bash_profile`、`.bash_login` 或 `.profile`，不改写用户文件。已有 `PROMPT_COMMAND` 保留，末尾追加应用提示符钩子，并返回进入钩子时的退出状态。

本地交互 Bash 使用 `--rcfile ... -i`；登录配置会读取，但进程不再由 `-il` 启动。依赖 `login_shell` 标志或 `.bash_logout` 的自定义脚本需要单独核对。后台复用 shell、PRoot/Chroot 登录和远端 SSH 的原有执行接口保持。

提示符使用 `ESC ] 777 ; operit-prompt ; 目录 BEL`。目录中的百分号和控制字符编码后发送，Bash 以 `\[...\]` 声明整个提示符的零宽度。`ShellPromptStream` 按会话缓冲跨块前缀，在进入屏幕和命令输出管线前分离该标记，并恢复目录。远端 SSH 仍识别服务端实际提示符。

首个本地标记推进会话到 READY；后续标记只更新目录和交互等待状态。工具命令仍以现有 `__OPERIT_COMMAND_DONE__:<命令ID>:<退出码>` 结束，不能在原命令与注入的 `printf` 之间提前结束。命令正文中的 `$`、`#` 不再被本地提示符误判吞掉。

输出使用持续 UTF-8 解码。ANSI 渲染缓冲不完整的 CSI、OSC 和 DCS 序列，未闭合控制序列的缓存上限为 64 Ki 字符。全屏切换之前先渲染数据块，并继续处理同块中的退出后正文与完成标记。

## 输入接口

`CanvasTerminalView` 的输入法确认动作、输入法按键事件、实体键盘和多个字符提交汇合到同一输入回调。

- 普通文本、中文候选定稿与多行提交，换行统一为终端回车
- 回车与小键盘回车，输入法发送、完成、前往、搜索等动作
- 退格、向前删除、Tab、Shift+Tab、Escape
- 方向、Home/End、Insert、Page Up/Down 与 F1 到 F12
- Ctrl 控制字符、Alt 前缀、带修饰参数的方向与功能键，以及实体键盘重音组合和右 Alt 字符输入

`TerminalInputConnection` 只缓存尚未定稿的输入法文字，定稿后一次提交，支持按字符或 Unicode 码点删除。确认动作把未定稿文字与回车一起提交。旧输入连接关闭后拒绝后续提交，切换模拟器和释放视图时清理连接与按键状态。

`applyTerminalModifiers` 供主应用与独立终端页面共用，避免 Ctrl 把转义序列中的字母转成控制字符。方向键遵守应用光标键模式，普通序列与带修饰参数的序列分别保留其语义。长按由 Android 按键重复事件驱动，不额外创建重复发送任务。

`TerminalManager.sendInput` 同步记录输入发生时的会话，再由单一消费者按顺序写入，避免异步任务重排，以及切换标签后字符进入另一个会话。会话关闭时丢弃属于该会话的待写入事件；管理器清理时取消队列。

## 光标

`RenderConfig.cursorColor` 默认采用前景色；`ConfigurableCanvasTerminal` 与独立输入框使用一致颜色。Canvas 绘制约 1.5 dp 的细竖线，默认每 500 ms 切换显隐，输入后重新开始显示。失焦时保持稳定显示，应用通过 ANSI 隐藏光标时仍尊重其设置。闪烁使用现有渲染线程与单调时钟，离开页面后不留下独立定时任务。

## 验证边界

当前为本地源码修改，仅进行 Kotlin、XML、Shell 语法及差异静态核对。没有运行 Android 编译、构建或测试，也没有实机输入法、实体键盘或真实 GitHub OAuth 的验证结果。主应用文档保存完整回归操作和单独结果表；不能将已补齐的输入路径表述为 OAuth 已验证成功。
