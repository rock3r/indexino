package dev.sebastiano.indexino.engine

/** Platform transport selected for runtime-owned scope watchers. */
internal enum class AutoRefreshWatcherKind {
    MAC_FSEVENTS,
    LINUX_INOTIFY,
    WINDOWS_WATCH_SERVICE,
}

/**
 * Selects the platform watcher transport without exposing platform details to refresh scheduling.
 */
internal object AutoRefreshWatcherFactory {
    fun configuredKind(
        platformName: String = System.getProperty("os.name"),
        override: String? = System.getProperty("indexino.autoRefreshWatcherKind"),
    ): AutoRefreshWatcherKind =
        override?.let(AutoRefreshWatcherKind::valueOf) ?: kindForPlatform(platformName)

    fun kindForPlatform(
        platformName: String = System.getProperty("os.name")
    ): AutoRefreshWatcherKind =
        when {
            platformName.startsWith("Mac", ignoreCase = true) -> AutoRefreshWatcherKind.MAC_FSEVENTS
            platformName.startsWith("Windows", ignoreCase = true) ->
                AutoRefreshWatcherKind.WINDOWS_WATCH_SERVICE
            else -> AutoRefreshWatcherKind.LINUX_INOTIFY
        }
}
