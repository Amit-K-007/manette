package com.manette.app.protocol

/** u16 serial-number arithmetic: wraps safely, and half the number space counts as "newer". */
object SequenceNumber {
    fun isNewer(last: Int, candidate: Int): Boolean {
        val diff = (candidate - last) and 0xFFFF
        val signed = if (diff >= 0x8000) diff - 0x10000 else diff
        return signed > 0
    }
}
