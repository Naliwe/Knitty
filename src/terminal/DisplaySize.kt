package knitty.terminal

import kotlin.math.roundToLong

internal fun displaySize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var amount = bytes.toDouble()
    var unit = -1
    do {
        amount /= 1024
        unit++
    } while (amount >= 1024 && unit < units.lastIndex)

    val tenths = (amount * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}
