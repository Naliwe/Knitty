package knitty.tui

import com.googlecode.lanterna.*
import com.googlecode.lanterna.screen.Screen
import knitty.terminal.plainText

internal object TuiPalette {
    val background: TextColor = TextColor.Indexed(235)
    val surface: TextColor = TextColor.Indexed(237)
    val text: TextColor = TextColor.Indexed(223)
    val muted: TextColor = TextColor.Indexed(246)
    val accent: TextColor = TextColor.Indexed(215)
    val border: TextColor = TextColor.Indexed(240)
    val added: TextColor = TextColor.Indexed(150)
    val removed: TextColor = TextColor.Indexed(210)
}

internal class ScreenCanvas(private val screen: Screen) {
    val width: Int = screen.terminalSize.columns
    val height: Int = screen.terminalSize.rows
    private val graphics = screen.newTextGraphics()

    init {
        screen.cursorPosition = null
        // Clear only the back buffer: Screen.clear() forces a terminal-wide erase on every refresh.
        graphics.foregroundColor = TuiPalette.text
        fill(0, 0, width, height)
    }

    fun fill(x: Int, y: Int, columns: Int, rows: Int, background: TextColor = TuiPalette.background) {
        if (columns <= 0 || rows <= 0) return
        graphics.backgroundColor = background
        graphics.fillRectangle(TerminalPosition(x, y), TerminalSize(columns, rows), ' ')
    }

    fun text(
        x: Int,
        y: Int,
        value: String,
        columns: Int = width - x,
        color: TextColor = TuiPalette.text,
        background: TextColor = TuiPalette.background,
        bold: Boolean = false,
    ) {
        val available = columns.coerceAtMost(width - x)
        if (x < 0 || y !in 0 until height || available <= 0) return

        graphics.foregroundColor = color
        graphics.backgroundColor = background
        graphics.disableModifiers(SGR.BOLD)
        if (bold) graphics.enableModifiers(SGR.BOLD)
        graphics.putString(x, y, TerminalTextUtils.fitString(plainText(value), available))
        graphics.disableModifiers(SGR.BOLD)
    }

    fun rule(y: Int, x: Int = 1, columns: Int = width - 2) {
        text(x, y, "─".repeat(columns.coerceAtLeast(0)), columns, TuiPalette.border)
    }

    fun header(context: String, section: String) {
        fill(0, 0, width, 1, TuiPalette.surface)
        text(2, 0, "knitty", color = TuiPalette.accent, background = TuiPalette.surface, bold = true)
        text(10, 0, section, color = TuiPalette.text, background = TuiPalette.surface)
        text(2, 1, context, color = TuiPalette.muted)
    }

    fun footer(primary: String, secondary: String) {
        rule(height - 3)
        text(2, height - 2, primary, width - 4, TuiPalette.accent)
        text(2, height - 1, secondary, width - 4, TuiPalette.muted)
    }

    fun tooSmall(): Boolean {
        if (width >= 40 && height >= 12) return false
        text(0, 0, "Resize terminal to at least 40 x 12.", color = TuiPalette.accent)
        text(0, 1, "Esc: return / quit")
        screen.refresh()
        return true
    }
}
