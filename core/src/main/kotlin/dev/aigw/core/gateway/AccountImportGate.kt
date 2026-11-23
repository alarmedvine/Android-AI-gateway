package dev.aigw.core.gateway

/**
 * 临时网页登录的提交门。网络校验在门外；只有最终账号写入与取消互斥。
 * 取消先取得门则以后不得落池，提交先取得门则保留已经完成的账号，不做破坏性回滚。
 */
class AccountImportGate {
    private var closed = false

    @Synchronized
    fun isOpen(): Boolean = !closed

    @Synchronized
    fun commit(writeAccount: () -> Unit): Boolean {
        if (closed || Thread.currentThread().isInterrupted) return false
        writeAccount()
        closed = true
        return true
    }

    @Synchronized
    fun cancel() {
        closed = true
    }
}
