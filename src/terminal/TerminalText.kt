package knitty.terminal

internal fun plainText(value: String): String = buildString(value.length) {
    for (character in value) {
        append(if (character.isISOControl()) ' ' else character)
    }
}
