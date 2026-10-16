# 宿主提供 Ubuntu 安装归档

个人发行分支不再随 Android 库打包 ubuntu-noble-aarch64-pd-v4.18.0.tar.xz。宿主需在创建 TerminalManager 单例之前注册归档准备接口：

```kotlin
TerminalManager.setUbuntuArchivePreparer { context, destination ->
    // 宿主先校验固定资源，再复制为终端安装流程使用的工作副本。
    OfflineResources.prepareUbuntuArchive(context, destination)
}
```

回调只在 Ubuntu 尚未安装时调用。已有 .operit_installed_ok 或 etc/issue.net 的环境沿用原来的识别规则，不需要归档或网络。小型 setup_fake_sysdata.sh 仍随库携带，每次初始化刷新。

回调应只准备已经下载或本地导入的资源，缺失时提示用户完成准备后重试，不应在现有会话 30 秒初始化等待期内下载大型文件。异常会使当前初始化失败，取消继续向上传递。

安装脚本会删除 destination 的归档，因此宿主需要单独保留可导出的原始资源缓存。本接口不改变已安装系统的用户文件、会话与命令完成事件。


## 删除与重新安装

宿主删除已安装的 Ubuntu 和安装归档时，调用 `TerminalManager.withEnvironmentMaintenance(context) { ... }`，在回调中删除应用私有目录中的环境及资源。该操作停止现有终端会话和 SSHD，阻止新的会话启动，并重置初始化状态；未创建终端管理器时不会为删除操作创建默认会话。删除开始后不响应取消，宿主应显示不可取消的删除进度。

删除完成后，宿主重新读取安装标记及资源校验结果。用户重新导入符合资源校验规则的安装归档后，下次初始化重新调用归档准备接口。

环境配置先执行 `apt-get update` 和 `apt-get --fix-broken install -y`，再执行 `dpkg --configure -a`，避免缺失依赖时首条配置命令失败，导致后续修复被命令链跳过。


删除维护接口在删除首个文件前，用 AtomicFile 将待删除记录写入 `terminal-maintenance/ubuntu-removal`，完整成功后才清除记录。记录存在时，`isEnvironmentRemovalPending(context)` 返回 true，初始化、新会话和 provider 获取均阻止使用未清理完的环境。

宿主在主进程启动时调用 `withEnvironmentMaintenance(context, resumeOnly = true) { ... }` 继续清理。恢复请求在维护互斥锁内检查记录，已完成的恢复不会再次执行删除回调。`environmentMaintenanceState` 可用于刷新恢复进度和最终状态。Android 终止进程后删除暂停，下次启动恢复，不承诺进程终止期间持续执行。
