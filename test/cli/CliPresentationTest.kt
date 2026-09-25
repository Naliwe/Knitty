package knitty.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import knitty.sampleSync
import knitty.sampleUpdate
import knitty.terminal.displaySize
import kotlin.test.*

class CliPresentationTest {
    @Test
    fun plansRetainExactPinsAndActionsWithOrWithoutColor() {
        val plain = Terminal(ansiLevel = AnsiLevel.NONE, interactive = false)
        val color = Terminal(ansiLevel = AnsiLevel.TRUECOLOR, interactive = false)
        val lines = renderUpdates(sampleUpdate) + renderSyncPlan(sampleSync)
        val unstyled = lines.joinToString("\n") { plain.render(it) }
        val styled = lines.joinToString("\n") { color.render(it) }

        assertFalse(unstyled.contains('\u001b'))
        assertTrue(styled.contains('\u001b'))
        assertContains(unstyled, "1.0 (file 10) -> 2.0 (file 11)")
        assertContains(unstyled, "1 install · 0 remove · 0 keep")
        assertContains(unstyled, "Outside profile, preserved: manual")
        assertContains(unstyled, "Download: 3 B")
        assertContains(unstyled, ":: Sync plan")
        assertContains(unstyled, "+ Example")
    }

    @Test
    fun downloadSizesAreReadableWithoutLocaleDependentFormatting() {
        assertEquals("0 B", displaySize(0))
        assertEquals("1023 B", displaySize(1023))
        assertEquals("1.0 KiB", displaySize(1024))
        assertEquals("1.5 MiB", displaySize(1572864))
        assertEquals("2.0 GiB", displaySize(2147483648))
    }
}
