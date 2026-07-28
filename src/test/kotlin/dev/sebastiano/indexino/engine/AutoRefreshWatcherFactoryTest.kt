package dev.sebastiano.indexino.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoRefreshWatcherFactoryTest {
    @Test
    fun `macOS selects native FSEvents transport`() {
        assertEquals(
            AutoRefreshWatcherKind.MAC_FSEVENTS,
            AutoRefreshWatcherFactory.kindForPlatform("Mac OS X"),
        )
    }

    @Test
    fun `Linux and Windows select their supported transports`() {
        assertEquals(
            AutoRefreshWatcherKind.LINUX_INOTIFY,
            AutoRefreshWatcherFactory.kindForPlatform("Linux"),
        )
        assertEquals(
            AutoRefreshWatcherKind.WINDOWS_WATCH_SERVICE,
            AutoRefreshWatcherFactory.kindForPlatform("Windows 11"),
        )
    }
}
