# 命令生命周期与会话退役

`TerminalManager.cancelCommand(sessionId, commandId)` 只取消匹配的命令。排队项从队列移除并收到取消完成事件；运行项先暂停队列推进，再发送 Ctrl+C。限时内无法完成时清理本地 PTY 同 SID 成员并关闭会话，返回 `CommandCancellationOutcome`，区分已移除、已中断、会话已关闭、清理未确认和已经结束。

本地可见会话取消时，Ctrl+C 发送后的 OSC 目录提示符只触发随机令牌确认行；只有当前命令与取消请求的回复匹配，才确认原 shell 已恢复。原尾部完成标记被终端输入清空时，不再仅因标记缺失就关闭已恢复的会话。确认行由 `builtin printf` 生成控制序列，不切换目录、不创建子 shell、不写入环境变量；输入回显、旧回复及关闭后的回复不会完成下一条命令。恢复确认继续使用每个会话的输入队列，过期取消实例的输入在发送前被丢弃。

原完成标记先到时保留真实退出码；只有恢复回复时，`exitCode` 为空且 `terminationReason=cancelled`，不把未知状态填成成功码。无法在取消期限内确认恢复时仍退役会话；退役前复查命令状态。SSH 不使用本地 OSC 恢复确认。

`CommandLifecycle` 在 `TerminalSessionData.copy` 后共享，用于串行化提交、取消、输出完成与关闭。环境维护等待异步退役结束后才继续删除。资源清理不在可重试的 StateFlow 更新回调内执行，每个会话的输入由独立消费者串行写入，阻塞写入不持有生命周期锁。

显式关闭、EOF、读取故障和主进程退出都会为运行及所有排队命令发出一次结束事件。命令退出码 `exitCode` 与会话退出码 `sessionExitCode` 分开保存；未知退出不写成成功码。独立监视器默认每 250ms 检查主进程，退出后最多等待读取排空 1000ms，读取故障另有限时观察。

本地 PTY 通过 `/proc/PID/stat` 的启动时间检查身份，识别 Z/X 状态，缓存 waitpid 回收结果。退役同 SID 进程时先通知 PRoot，再清理仍存活成员；每次发信号前核对身份。脱离原 SID 的程序不属于同会话清理保证。JNI 只补充 waitpid 的 EINTR 重试和返回值检查，保持现有 CMake 3.22.1。

`createNewSession(title, automation = true)` 为新建本地会话传递 `PAGER=cat`、`GIT_PAGER=cat`、`GIT_TERMINAL_PROMPT=0`，SSH 与人工会话保持各自原有环境。公共旧调用保留默认参数，宿主[本地初始化接口](local_session_initializer.md)继续在就绪前执行。common.sh 使用同目录临时文件、同步和原子 rename，避免并发读取半写文件。

参考 CATMIAOZHI/OperitTerminalCore 的 [10d3541](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/10d3541f0fbb2003246d772c11535f88b6f06335)、[63fd584](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/63fd58471e4978d44e8ad0fc9728d535afbc39ac)、[67f03e6](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/67f03e63ba44927baf2f7a4fe306f29332bc9890)、[cd5d53c](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/cd5d53cb2c99b7913d01c7919e1d93bb55ca76c4)、[0c238c9](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/0c238c9bc8bbf581688af721da75be8bde2ce3c4)、[362a726](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/362a726631060732a8bd1c5dec673180eb5ac2c9)、[afb8b35](https://github.com/CATMIAOZHI/OperitTerminalCore/commit/afb8b35780a17a4c4a9da8499f1ef229e11d69ef) 按个人代码适配；保留个人 UTF-8 解码、命令完成标记和交互识别规则。

回归用例位于 src/test，覆盖进程身份解析、有限输出排空和关闭时恰好一次完成；另有 13 个取消恢复用例检查分块、提示符时序、旧令牌、输入回显、未知退出码、队列保留和迟到回复。终端库通过宿主应用的 `:terminal:testDebugUnitTest` 与 Debug 构建验证，本轮未在本地执行编译或测试。
