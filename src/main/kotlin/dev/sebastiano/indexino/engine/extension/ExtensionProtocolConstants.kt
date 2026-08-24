package dev.sebastiano.indexino.engine.extension

internal object ExtensionProtocolConstants {
    const val PROTOCOL_MAJOR: Int = 1
    const val MAX_CONCURRENT_WORKERS: Int = 2
    const val MAX_QUERIES_PER_CHECK: Int = 256
    const val MAX_FINDINGS_PER_CHECK: Int = 10_000
    const val DEFAULT_CHECK_DEADLINE_MILLIS: Long = 120_000L
    const val WORKER_MAX_HEAP: String = "256m"
    const val SESSION_SUFFIX_LENGTH: Int = 8
    const val THREAD_JOIN_TIMEOUT_MILLIS: Long = 5_000L
    const val PROCESS_DESTROY_TIMEOUT_SECONDS: Long = 5L
}

internal class ExtensionProtocolException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)
