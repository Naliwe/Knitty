package knitty.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.rendering.Whitespace
import knitty.terminal.plainText

internal object CliStyle {
    val accent = TextColors.rgb("#efa45b") + bold
    val added = TextColors.rgb("#b8c78b")
    val removed = TextColors.rgb("#ed9385")

    fun heading(text: String): String = "${accent("::")} ${bold(plainText(text))}"
    fun muted(text: String): String = dim(plainText(text))
    fun name(text: String): String = bold(plainText(text))
    fun version(text: String): String = accent(plainText(text))
    fun change(symbol: String, text: String): String {
        val style = when (symbol) {
            "+" -> added
            "-" -> removed
            "~" -> accent
            else -> dim.style
        }
        return "  ${style(symbol)} ${plainText(text)}"
    }
}

internal fun CliktCommand.printLines(lines: List<String>) {
    lines.forEach { printLine(it) }
}

internal fun CliktCommand.printLine(text: String) {
    terminal.println(text, whitespace = Whitespace.PRE_WRAP, overflowWrap = OverflowWrap.BREAK_WORD)
}

internal fun CliktCommand.printStatus(text: String) = printLine(CliStyle.heading(text))
