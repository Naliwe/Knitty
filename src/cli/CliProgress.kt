package knitty.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.animation.textAnimation
import com.github.ajalt.mordant.terminal.Terminal
import knitty.core.model.OperationProgress
import knitty.core.model.ProgressSink
import knitty.core.model.ProgressStage
import knitty.terminal.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal fun <T> CliktCommand.withProgress(
    stage: ProgressStage,
    action: suspend (ProgressSink) -> T,
): T = runBlocking {
    val display = CliProgress(terminal)
    display.report(OperationProgress(stage))
    val animation = if (terminal.terminalInfo.outputInteractive) {
        launch {
            while (isActive) {
                delay(100.milliseconds)
                display.tick()
            }
        }
    } else {
        null
    }

    try {
        action(display)
    } finally {
        animation?.cancel()
        display.close()
    }
}

internal class CliProgress(private val terminal: Terminal) : ProgressSink {
    private val lock = Any()
    private val animation = terminal.textAnimation<String> { it }
    private val interactive = terminal.terminalInfo.outputInteractive
    private var current: OperationProgress? = null
    private var lastDraw = TimeSource.Monotonic.markNow()
    private var frame = 0L
    private var closed = false

    override fun report(progress: OperationProgress) = synchronized(lock) {
        if (closed) return@synchronized

        val previous = current
        val changed = previous?.stage != progress.stage || previous.item != progress.item
        if (changed) finishLine()
        if (previous?.stage != progress.stage) terminal.println(CliStyle.heading(progressTitle(progress.stage)))

        current = progress
        if (interactive && (changed || progressFraction(progress) == 1.0 || lastDraw.elapsedNow() >= 100.milliseconds)) {
            draw()
        }
    }

    fun tick() = synchronized(lock) {
        if (current != null && interactive) {
            frame++
            draw()
        }
    }

    fun close() = synchronized(lock) {
        finishLine()
        current = null
        closed = true
    }

    private fun draw() {
        val progress = current
            ?: return

        animation.update(line(progress))
        lastDraw = TimeSource.Monotonic.markNow()
    }

    private fun finishLine() {
        val progress = current
            ?: return

        if (interactive) {
            draw()
            animation.stop()
        } else if (progress.item != null || progress.total != null) {
            terminal.println(line(progress))
        }
    }

    private fun line(progress: OperationProgress): String {
        val width = (terminal.size.width / 4).coerceIn(5, 30)
        val item = progress.item?.let { plainText(it) + "  " }.orEmpty()

        return "  $item[${progressBar(progress, width, frame)}] ${progressAmount(progress)}"
    }
}
