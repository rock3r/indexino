package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.engine.RuntimeFrameCodec

/** Reuses runtime frame limits; extension protocol is a separate domain socket. */
internal object ExtensionFrameCodec {
    const val MAX_FRAME_BYTES: Int = RuntimeFrameCodec.MAX_FRAME_BYTES

    fun write(output: java.io.DataOutputStream, payload: ByteArray): Unit =
        RuntimeFrameCodec.write(output, payload)

    fun read(input: java.io.DataInputStream): ByteArray =
        try {
            RuntimeFrameCodec.read(input)
        } catch (failure: dev.sebastiano.indexino.engine.RuntimeProtocolException) {
            throw ExtensionProtocolException(
                failure.message ?: "Extension frame read failed",
                failure,
            )
        }
}
