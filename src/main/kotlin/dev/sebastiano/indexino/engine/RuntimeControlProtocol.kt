package dev.sebastiano.indexino.engine

internal object RuntimeControlProtocol {
    const val SHUTDOWN = 4

    fun shutdownCommand(): ByteArray = byteArrayOf(SHUTDOWN.toByte())
}
