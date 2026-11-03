# 本地会话初始化钩子

宿主可以在第一次创建 `TerminalManager` 实例前调用 `TerminalManager.setLocalSessionInitializer((Context) -> String)`，提供在 Ubuntu 内执行的初始化命令。钩子默认是空操作，现有独立终端宿主无需修改调用方式。

回调在生成登录脚本时运行，适合提取并校验宿主的小型资源；返回值是 Ubuntu 内执行的 Bash 命令。回调抛出异常时环境初始化失败。返回的命令通过完整 shell 引用及独立环境变量传递，不在宿主 shell 展开。真实 chroot 使用独立文件传入相同内容。

普通交互终端和隐藏执行都调用 `login_ubuntu`。proot 的两种链接模式及真实 chroot 均先执行此初始化命令，成功后才输出 `LOGIN_SUCCESSFUL` 和 `TERMINAL_READY`，再执行原会话命令。初始化返回非零时当前登录退出，不执行会话任务。SSH 远程会话不使用此本地钩子。

Operit 主应用用此入口安装 APK 附带的 `apply_patch`，实现位于主应用独立包；终端核心不依赖该命令或主应用类。接口保留原有 Ubuntu 归档准备和可见/隐藏终端调用契约。

本次只完成静态核对。本地未执行构建或测试；编译及宿主集成由主仓库引用该提交的工作流验证。
