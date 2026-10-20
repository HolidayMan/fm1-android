package org.fm1.android

/** Every operation on a native handle belongs to its owning serial worker. */
internal object NativeEngine {
    init {
        System.loadLibrary("fm1android")
    }

    @JvmStatic external fun create(packageBytes: ByteArray, requiredKind: Int): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun kind(handle: Long): Int

    @JvmStatic external fun render(handle: Long, pcm: FloatArray, frames: Int): Int

    @JvmStatic external fun key(handle: Long, id: Int, down: Boolean)

    @JvmStatic external fun release(handle: Long)

    @JvmStatic external fun encoder(handle: Long, id: Int, delta: Int)

    @JvmStatic external fun volume(handle: Long, value: Int)

    @JvmStatic external fun snapshot(handle: Long, pixels: IntArray, leds: FloatArray): Int

    @JvmStatic external fun flash(handle: Long): ByteArray

    @JvmStatic external fun restore(handle: Long, flash: ByteArray): Boolean

    @JvmStatic external fun readPart(handle: Long, type: Int, id: Int): ByteArray

    @JvmStatic external fun writePart(handle: Long, type: Int, id: Int, bytes: ByteArray): Int

    @JvmStatic external fun transport(handle: Long, playing: Boolean): Boolean

    @JvmStatic external fun commit(handle: Long): Int
}
