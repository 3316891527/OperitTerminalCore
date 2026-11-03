package com.ai.assistance.operit.terminal.process

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 主进程退出后有限等待尾部输出，后代持有文件描述符不能永久阻塞完成通知。 */
internal suspend fun awaitTerminalExit(
    isAlive: () -> Boolean,
    reader: Job,
    pollMs: Long = 250L,
    drainMs: Long = 1_000L,
): Boolean {
    while (isAlive()) delay(pollMs)
    return withTimeoutOrNull(drainMs) { reader.join(); true } == true
}
