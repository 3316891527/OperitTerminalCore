package com.ai.assistance.operit.terminal.maintenance

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** 删除意图位于 Ubuntu 根目录之外，进程退出后仍能识别未完成的删除。 */
internal class UbuntuRemovalJournal(filesDir: File) {
    private val file = File(filesDir, "terminal-maintenance/ubuntu-removal")
    private val atomicFile = AtomicFile(file)

    fun isPending(): Boolean = file.exists() || File(file.path + ".bak").exists()

    fun begin() {
        if (isPending()) return
        val stream = atomicFile.startWrite()
        try {
            stream.write("pending\n".toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
        // 只有原子文件完成写入，才允许删除第一个终端文件。
        if (!isPending()) throw IOException("Cannot persist Ubuntu removal journal")
    }

    fun finish() {
        atomicFile.delete()
        if (isPending()) throw IOException("Cannot clear Ubuntu removal journal")
    }
}
