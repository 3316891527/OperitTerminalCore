# 独立发行终端核心

本分支保留上游 master 的终端接口，并整合以下修复提交，供 Operit 独立发行版通过子模块固定引用。

- [PR #4](https://github.com/AAswordman/OperitTerminalCore/pull/4)：`46358e46c19a51876764560978d3cab589c1e9d7`，fix terminal command completion, exit status, and PTY session recovery
- [PR #7](https://github.com/AAswordman/OperitTerminalCore/pull/7)：`c326faed23e79ae2af2cfa50c431ae5d62474d96`，fix(terminal): kill the whole proot/bash process group on close
- [PR #8](https://github.com/AAswordman/OperitTerminalCore/pull/8)：`3d7bde326fb776e4d7d9c21a23cb2bbcde630386`，fix(hidden-exec): recycle timed-out shells
- [PR #9](https://github.com/AAswordman/OperitTerminalCore/pull/9)：`99c126756f464ad8ac5e6309af4d7cd5bafc96c0`，fix(chroot): restore apt ownership for proot
- [PR #10](https://github.com/AAswordman/OperitTerminalCore/pull/10)：`9f60e954c61ad276ac63407db6c6f3ce0695845a`，fix(chroot): report and verify bind mounts

## 协同行为

隐藏执行按实际 Shell 实例移除缓存，清理整个进程组，并保留超时后的强制终止处理。关闭流程不再无限等待进程退出，避免回收动作本身持续挂起。PTY 存活探测使用能报告进程不存在的 `Os.kill(pid, 0)`，供 `Process.isAlive` 判断。

命令完成事件提供真实退出码，普通输出事件不要求退出码。宿主应在完成事件中读取 `exitCode`，而不是将所有命令统一标记为退出码 0。chroot wrapper 同时保留 APT 属主恢复和 bind mount 状态诊断。

构建与 JVM 验证由引用此指针的 Operit 发行分支 GitHub Actions 执行。本地只进行源码、祖先关系与 diff 检查；进程清理、chroot/proot 往返及真实挂载行为仍需设备回归。
