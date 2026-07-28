package dev.sebastiano.indexino.engine

import java.lang.Void
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One native macOS FSEvents stream, scheduled on its dedicated CFRunLoop thread. */
internal class MacFseventsWatcher(
    private val root: Path,
    private val changed: (Path) -> Unit,
    private val overflowed: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val ready = CountDownLatch(1)
    private val arena = Arena.ofShared()
    private val id = NEXT_ID.incrementAndGet()
    private lateinit var stream: MemorySegment
    private lateinit var runLoop: MemorySegment
    private val thread =
        Thread(::run, "indexino-fsevents").apply {
            isDaemon = true
            start()
        }

    init {
        INSTANCES[id] = this
        ready.await()
    }

    internal fun flushForTests() {
        fsevents("FSEventStreamFlushSync", FLUSH_SYNC, stream)
    }

    internal fun callbackCountForTests(): Long = CALLBACK_COUNT.get()

    internal fun callbackIdForTests(): Long = LAST_CALLBACK_ID.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (::stream.isInitialized) {
            fsevents("FSEventStreamStop", STOP, stream)
            fsevents("FSEventStreamInvalidate", INVALIDATE, stream)
        }
        if (::runLoop.isInitialized) coreFoundation("CFRunLoopStop", RUN_LOOP_STOP, runLoop)
        thread.join(JOIN_TIMEOUT_MILLIS)
        INSTANCES.remove(id)
        if (::stream.isInitialized) STREAMS.remove(stream.address())
        if (::stream.isInitialized) fsevents("FSEventStreamRelease", RELEASE, stream)
        arena.close()
    }

    private fun run() {
        try {
            val rootString =
                coreFoundation(
                    "CFStringCreateWithCString",
                    CREATE_STRING,
                    NULL,
                    utf8(root.toString()),
                    UTF8,
                )
                    as MemorySegment
            val values =
                arena.allocate(ValueLayout.ADDRESS.byteSize(), ValueLayout.ADDRESS.byteAlignment())
            values.set(ValueLayout.ADDRESS, 0, rootString)
            val paths =
                coreFoundation(
                    "CFArrayCreate",
                    CREATE_ARRAY,
                    NULL,
                    values,
                    1L,
                    CORE_FOUNDATION.find("kCFTypeArrayCallBacks").orElseThrow(),
                )
                    as MemorySegment
            val info = arena.allocate(ValueLayout.JAVA_LONG, id)
            val context = arena.allocate(FSEVENT_STREAM_CONTEXT_SIZE, POINTER_ALIGNMENT)
            context.set(ValueLayout.JAVA_LONG, FSEVENT_STREAM_CONTEXT_VERSION_OFFSET, 0L)
            context.set(ValueLayout.ADDRESS, FSEVENT_STREAM_CONTEXT_INFO_OFFSET, info)
            context.set(ValueLayout.ADDRESS, FSEVENT_STREAM_CONTEXT_RETAIN_OFFSET, NULL)
            context.set(ValueLayout.ADDRESS, FSEVENT_STREAM_CONTEXT_RELEASE_OFFSET, NULL)
            context.set(ValueLayout.ADDRESS, FSEVENT_STREAM_CONTEXT_COPY_DESCRIPTION_OFFSET, NULL)
            val callback = LINKER.upcallStub(CALLBACK, CALLBACK_DESCRIPTOR, arena)
            stream =
                fsevents(
                    "FSEventStreamCreate",
                    CREATE_STREAM,
                    NULL,
                    callback,
                    context,
                    paths,
                    SINCE_NOW,
                    LATENCY_SECONDS,
                    FILE_EVENTS,
                )
                    as MemorySegment
            check(stream != NULL) { "FSEventStreamCreate returned null" }
            STREAMS[stream.address()] = this
            runLoop = coreFoundation("CFRunLoopGetCurrent", RUN_LOOP_CURRENT) as MemorySegment
            val mode =
                CORE_FOUNDATION.find("kCFRunLoopDefaultMode")
                    .orElseThrow()
                    .reinterpret(8)
                    .get(ValueLayout.ADDRESS, 0)
            fsevents("FSEventStreamScheduleWithRunLoop", SCHEDULE, stream, runLoop, mode)
            check((fsevents("FSEventStreamStart", START, stream) as Byte).toInt() != 0) {
                "FSEventStreamStart failed"
            }
            ready.countDown()
            coreFoundation("CFRunLoopRun", RUN_LOOP_RUN)
        } finally {
            ready.countDown()
        }
    }

    private fun receive(eventPaths: MemorySegment, flags: MemorySegment, eventCount: Long) {
        if (closed.get() || eventCount == 0L) return
        val paths = eventPaths.reinterpret(eventCount * ValueLayout.ADDRESS.byteSize())
        val eventFlags = flags.reinterpret(eventCount * ValueLayout.JAVA_INT.byteSize())
        repeat(eventCount.toInt()) { index ->
            val offset = index.toLong()
            val flag =
                eventFlags.get(ValueLayout.JAVA_INT, offset * ValueLayout.JAVA_INT.byteSize())
            if (flag and OVERFLOW_FLAGS != 0) overflowed()
            val pointer = paths.get(ValueLayout.ADDRESS, offset * ValueLayout.ADDRESS.byteSize())
            runCatching { Path.of(pointer.reinterpret(Long.MAX_VALUE).getString(0)) }
                .onSuccess(changed)
        }
    }

    private fun fsevents(
        name: String,
        descriptor: FunctionDescriptor,
        vararg arguments: Any?,
    ): Any? =
        LINKER.downcallHandle(FSEVENTS.find(name).orElseThrow(), descriptor)
            .invokeWithArguments(*arguments)

    private fun coreFoundation(
        name: String,
        descriptor: FunctionDescriptor,
        vararg arguments: Any?,
    ): Any? =
        LINKER.downcallHandle(CORE_FOUNDATION.find(name).orElseThrow(), descriptor)
            .invokeWithArguments(*arguments)

    private fun utf8(value: String): MemorySegment {
        val bytes = (value + "\u0000").encodeToByteArray()
        return arena.allocate(bytes.size.toLong(), 1).also { segment ->
            segment.asByteBuffer().put(bytes)
        }
    }

    private companion object {
        private const val UTF8 = 0x08000100
        private const val FILE_EVENTS = 0x00000010
        private const val OVERFLOW_FLAGS = 0x0000000F
        private const val SINCE_NOW = -1L
        private const val LATENCY_SECONDS = 0.1
        private const val FSEVENT_STREAM_CONTEXT_SIZE = 40L
        private const val FSEVENT_STREAM_CONTEXT_VERSION_OFFSET = 0L
        private const val FSEVENT_STREAM_CONTEXT_INFO_OFFSET = 8L
        private const val FSEVENT_STREAM_CONTEXT_RETAIN_OFFSET = 16L
        private const val FSEVENT_STREAM_CONTEXT_RELEASE_OFFSET = 24L
        private const val FSEVENT_STREAM_CONTEXT_COPY_DESCRIPTION_OFFSET = 32L
        private const val POINTER_ALIGNMENT = 8L
        private const val JOIN_TIMEOUT_MILLIS = 1_000L

        private val NULL = MemorySegment.NULL
        private val LINKER = Linker.nativeLinker()
        private val CORE_FOUNDATION =
            SymbolLookup.libraryLookup(
                "/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation",
                Arena.global(),
            )
        private val FSEVENTS =
            SymbolLookup.libraryLookup(
                "/System/Library/Frameworks/CoreServices.framework/Frameworks/FSEvents.framework/Versions/A/FSEvents",
                Arena.global(),
            )
        private val INSTANCES = ConcurrentHashMap<Long, MacFseventsWatcher>()
        private val NEXT_ID = AtomicLong()
        private val CALLBACK_COUNT = AtomicLong()
        private val LAST_CALLBACK_ID = AtomicLong()
        private val STREAMS = ConcurrentHashMap<Long, MacFseventsWatcher>()
        private val CALLBACK =
            MethodHandles.lookup()
                .findStatic(
                    MacFseventsWatcher::class.java,
                    "events",
                    MethodType.methodType(
                        Void.TYPE,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                        Long::class.javaPrimitiveType,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                    ),
                )
        private val CALLBACK_DESCRIPTOR =
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            )
        private val CREATE_STRING =
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            )
        private val CREATE_ARRAY =
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            )
        private val CREATE_STREAM =
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_INT,
            )
        private val SCHEDULE =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        private val START = FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS)
        private val STOP = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        private val INVALIDATE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        private val RELEASE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        private val FLUSH_SYNC = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        private val RUN_LOOP_CURRENT = FunctionDescriptor.of(ValueLayout.ADDRESS)
        private val RUN_LOOP_RUN = FunctionDescriptor.ofVoid()
        private val RUN_LOOP_STOP = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)

        @JvmStatic
        private fun events(
            stream: MemorySegment,
            info: MemorySegment,
            eventCount: Long,
            eventPaths: MemorySegment,
            flags: MemorySegment,
            eventIds: MemorySegment,
        ) {
            @Suppress("UNUSED_VARIABLE") val ignored = stream to eventPaths to flags to eventIds
            CALLBACK_COUNT.incrementAndGet()
            val id = info.address()
            LAST_CALLBACK_ID.set(id)
            STREAMS[stream.address()]?.receive(eventPaths, flags, eventCount)
        }
    }
}
