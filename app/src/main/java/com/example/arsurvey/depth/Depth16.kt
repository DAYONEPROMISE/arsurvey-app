package com.example.arsurvey.depth

/**
 * Single source of truth for decoding ARCore **DEPTH16** samples.
 *
 * `Frame.acquireDepthImage16Bits()` returns Android `ImageFormat.DEPTH16`: each 16-bit sample packs
 * the depth in the **low 13 bits** (millimeters) and a **3-bit confidence** in the top bits:
 *
 * ```
 *   bit 15 .. 13   bit 12 .. 0
 *   [confidence]   [depth mm]
 * ```
 *
 * Reading the raw 16-bit value as millimeters (`raw and 0xFFFF`) over-reads by up to +57344 mm when
 * confidence bits are set — far beyond ARCore's physical range — so **all** production decoding must
 * mask to the low 13 bits. A decoded value of `0` mm means "no depth estimated" (invalid / hole).
 */
object Depth16 {

    /** Low-13-bit mask isolating the millimeter depth from the confidence bits. */
    const val DEPTH_MASK = 0x1FFF

    /** Decoded depth in millimeters (0 = invalid / no return). */
    fun millimeters(raw: Short): Int = raw.toInt() and DEPTH_MASK

    /** 3-bit confidence packed above the depth (0..7). */
    fun confidence(raw: Short): Int = (raw.toInt() ushr 13) and 0x7

    /** Number of samples with a valid (non-zero) decoded depth. */
    fun countValid(depth: ShortArray): Int {
        var n = 0
        for (s in depth) if ((s.toInt() and DEPTH_MASK) != 0) n++
        return n
    }
}
