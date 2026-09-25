package knitty.tui

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.screen.Screen
import knitty.core.model.GameId
import knitty.core.model.ModSearchResult
import knitty.terminal.plainText

internal fun renderModDetails(
    screen: Screen,
    game: GameId,
    mod: ModSearchResult?,
    scroll: Int,
    profileActions: Boolean = true,
): Int {
    val canvas = ScreenCanvas(screen)
    if (canvas.tooSmall()) return 0

    canvas.header(game.value, "MOD DETAILS")
    canvas.rule(2)
    val details = mod?.let {
        listOf(
            it.name,
            "",
            it.summary,
            "",
            "Version  ${it.version ?: "No published version"}",
            "Author   ${it.author}",
            "Package  ${it.id.provider}:${it.id.value}",
            "",
            it.pageUrl,
        )
    }.orEmpty()
    val lines = details.flatMap { TerminalTextUtils.getWordWrappedText(canvas.width - 4, plainText(it)) }
    val rows = canvas.height - 7
    val start = scroll.coerceIn(0, (lines.size - rows).coerceAtLeast(0))
    lines.drop(start).take(rows).forEachIndexed { index, line ->
        val title = start + index == 0
        canvas.text(
            2,
            3 + index,
            line,
            canvas.width - 4,
            if (title) TuiPalette.accent else TuiPalette.text,
            bold = title,
        )
    }
    if (lines.size > rows) {
        canvas.text(
            2,
            canvas.height - 4,
            "${start + 1}–${(start + rows).coerceAtMost(lines.size)} / ${lines.size} lines",
            color = TuiPalette.muted,
        )
    }
    val actions = if (profileActions) "a add   Esc return   q quit" else "Esc return   q quit"
    canvas.footer("↑↓/jk scroll   PgUp/PgDn page", actions)
    screen.refresh()
    return start
}
