package knitty.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import knitty.core.application.ManageProfile
import knitty.core.application.SearchMods
import knitty.core.model.GameId
import knitty.core.model.OperationProgress
import knitty.core.model.SearchFailure
import knitty.terminal.plainText
import knitty.terminal.providerFailureMessage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

suspend fun openSearchScreen(
    game: GameId,
    searchMods: SearchMods,
    profiles: ManageProfile,
    target: String? = null,
) {
    DefaultTerminalFactory().setForceTextTerminal(true).createScreen().use { screen ->
        screen.startScreen()
        runSearchScreen(screen, game, searchMods, profiles, target)
    }
}

suspend fun runSearchScreen(
    screen: Screen,
    game: GameId,
    searchMods: SearchMods,
    profiles: ManageProfile,
    target: String? = null,
) =
    coroutineScope {
        val search = SearchController(game, searchMods, this)
        val profile = ProfileController(game, profiles, this, target)
        val profileActions = profiles.supports(game)

        var query = ""
        var editing = true
        var scroll = 0
        var help = false
        var details = false
        var previousFrame: SearchFrame? = null
        val animationStart = TimeSource.Monotonic.markNow()
        screen.cursorPosition = null

        try {
            while (isActive) {
                screen.doResizeIfNecessary()
                val state = profile.state.value
                val busy = state == ProfileScreenState.Applying
                val working = busy || state == ProfileScreenState.Planning
                val tick = if (working || search.state.value.loading) {
                    animationStart.elapsedNow().inWholeMilliseconds / 100
                } else {
                    0
                }
                if (state != previousFrame?.profile) scroll = 0

                val frame = SearchFrame(
                    search.state.value,
                    query,
                    editing,
                    screen.terminalSize,
                    state,
                    scroll,
                    help,
                    details,
                    if (working) profile.progress else null,
                    tick,
                )
                if (frame != previousFrame) {
                    if (help) {
                        renderSearchHelp(screen, profileActions)
                    } else if (details) {
                        scroll = renderModDetails(screen, game, frame.state.selected, scroll, profileActions)
                    } else if (state == ProfileScreenState.Closed) {
                        renderSearchScreen(screen, game, frame.state, query, editing, profileActions, target, tick)
                    } else {
                        scroll = renderProfileScreen(screen, state, scroll, frame.progress, tick)
                    }
                    previousFrame = frame.copy(scroll = scroll)
                }

                val key = screen.pollInput()
                when {
                    key == null -> delay(40.milliseconds)
                    key.keyType == KeyType.EOF -> break
                    key.isCtrlDown && key.character?.lowercaseChar() == 'c' && !busy -> break
                    help -> when {
                        key.keyType == KeyType.Escape || key.character == '?' -> help = false
                        key.character == 'q' -> break
                    }

                    details -> when {
                        key.keyType == KeyType.Escape || key.character == 'd' -> details = false
                        key.character == 'q' -> break
                        profileActions && key.character == 'a' -> search.state.value.selected?.let {
                            details = false
                            scroll = 0
                            profile.add(it.id)
                        }

                        key.keyType == KeyType.ArrowDown || key.character == 'j' -> scroll++
                        key.keyType == KeyType.ArrowUp || key.character == 'k' -> scroll = (scroll - 1).coerceAtLeast(0)
                        key.keyType == KeyType.PageDown -> scroll += (screen.terminalSize.rows - 7).coerceAtLeast(1)
                        key.keyType == KeyType.PageUp -> scroll =
                            (scroll - screen.terminalSize.rows + 7).coerceAtLeast(0)
                    }

                    !editing && key.character == '?' && state == ProfileScreenState.Closed -> help = true
                    state != ProfileScreenState.Closed -> when {
                        key.keyType == KeyType.Escape -> profile.dismiss()
                        key.character == 'q' && !busy -> break
                        key.character == 'y' -> profile.confirm()
                        key.character == 'u' && state is ProfileScreenState.Outdated -> {
                            scroll = 0
                            profile.update()
                        }

                        key.character == 's' && !busy -> {
                            scroll = 0
                            profile.sync()
                        }

                        key.keyType == KeyType.ArrowDown || key.character == 'j' -> scroll++
                        key.keyType == KeyType.ArrowUp || key.character == 'k' -> scroll = (scroll - 1).coerceAtLeast(0)
                        key.keyType == KeyType.PageDown -> scroll += (screen.terminalSize.rows - 7).coerceAtLeast(1)
                        key.keyType == KeyType.PageUp -> scroll =
                            (scroll - screen.terminalSize.rows + 7).coerceAtLeast(0)
                    }

                    key.keyType == KeyType.Escape -> editing = !editing
                    key.keyType == KeyType.Tab || key.keyType == KeyType.ReverseTab -> {
                        editing = !editing
                    }

                    editing && key.keyType == KeyType.ArrowDown -> editing = false
                    key.keyType == KeyType.Enter && editing -> {
                        search.search(query)
                        editing = false
                    }

                    editing && key.keyType == KeyType.Backspace -> {
                        if (query.isNotEmpty()) {
                            val lastCharacterSize = if (query.last().isLowSurrogate()) 2 else 1
                            query = query.dropLast(lastCharacterSize)
                        }
                    }

                    editing && key.keyType == KeyType.Character && !key.isCtrlDown && !key.isAltDown -> {
                        query += key.character
                    }

                    !editing && (key.keyType == KeyType.ArrowDown || key.character == 'j') -> search.select(1)
                    !editing && (key.keyType == KeyType.ArrowUp || key.character == 'k') -> search.select(-1)
                    !editing && key.keyType == KeyType.PageDown -> search.nextPage()
                    !editing && key.keyType == KeyType.PageUp -> search.previousPage()
                    !editing && key.character == '/' -> editing = true
                    !editing && (key.character == 'd' || key.keyType == KeyType.Enter) && search.state.value.selected != null -> {
                        scroll = 0
                        details = true
                    }

                    !editing && profileActions && key.character == 'a' -> search.state.value.selected?.let {
                        scroll = 0
                        profile.add(it.id)
                    }

                    !editing && profileActions && key.character == 'r' -> search.state.value.selected?.let {
                        scroll = 0
                        profile.remove(it.id)
                    }

                    !editing && profileActions && key.character == 's' -> {
                        scroll = 0
                        profile.sync()
                    }

                    !editing && profileActions && key.character == 'l' -> {
                        scroll = 0
                        profile.lock()
                    }

                    !editing && profileActions && key.character == 'o' -> {
                        scroll = 0
                        profile.outdated()
                    }

                    !editing && profileActions && key.character == 'u' -> {
                        scroll = 0
                        profile.update()
                    }

                    !editing && key.character == 'q' -> break
                }
            }
        } finally {
            search.close()
            profile.close()
        }
    }

private data class SearchFrame(
    val state: SearchState,
    val query: String,
    val editing: Boolean,
    val size: TerminalSize,
    val profile: ProfileScreenState,
    val scroll: Int,
    val help: Boolean,
    val details: Boolean,
    val progress: OperationProgress?,
    val tick: Long,
)

internal fun renderSearchScreen(
    screen: Screen,
    game: GameId,
    state: SearchState,
    query: String,
    editing: Boolean,
    profileActions: Boolean = true,
    target: String? = null,
    tick: Long = 0,
) {
    val canvas = ScreenCanvas(screen)
    if (canvas.tooSmall()) return

    val width = canvas.width
    val height = canvas.height
    val context = if (profileActions) game.value else "${game.value} · catalog only; installation unavailable"
    canvas.header(if (target == null) context else "$context · server: $target", "MOD CATALOG")
    canvas.fill(1, 3, width - 2, 1, TuiPalette.surface)
    canvas.text(2, 3, "/", color = TuiPalette.accent, background = TuiPalette.surface, bold = true)
    val inputWidth = width - 7
    var visibleQuery = plainText(query)
    while (TerminalTextUtils.getColumnWidth(visibleQuery) > inputWidth - 1) {
        val firstCharacterSize = if (visibleQuery.first().isHighSurrogate()) 2 else 1
        visibleQuery = visibleQuery.drop(firstCharacterSize)
    }
    canvas.text(
        5, 3,
        if (query.isEmpty() && !editing) "Search mods…" else visibleQuery,
        inputWidth,
        if (editing) TuiPalette.text else TuiPalette.muted,
        TuiPalette.surface,
    )
    if (editing) screen.cursorPosition = TerminalPosition(5 + TerminalTextUtils.getColumnWidth(visibleQuery), 3)

    val page = state.page
    val status = when {
        state.loading -> "${"|/-\\"[(tick % 4).toInt()]} Searching…"
        state.failure != null -> failureMessage(state.failure)
        page == null -> "Find something for your next session."
        page.mods.isEmpty() -> "No mods found. Try a shorter query."
        else -> "${page.offset + 1}–${page.offset + page.mods.size} of ${page.total} results"
    }
    // Keep provider failures readable at narrow widths instead of silently clipping the recovery hint.
    val statusLines = TerminalTextUtils.getWordWrappedText(width - 4, plainText(status))
    val statusRows = statusLines.size.coerceAtMost((height - 8).coerceAtLeast(1))
    statusLines.take(statusRows).forEachIndexed { index, line ->
        canvas.text(2, 5 + index, line, width - 4, if (state.failure != null) TuiPalette.removed else TuiPalette.muted)
    }
    val top = 6 + statusRows
    val bottom = height - 3
    if (page == null || page.mods.isEmpty()) {
        if (bottom - top >= 3 && state.failure == null) {
            canvas.text(
                2,
                top + 1,
                if (state.loading) "Looking through the catalog…" else "Search. Review. Play.",
                color = TuiPalette.accent,
                bold = true,
            )
            canvas.text(2, top + 2, "Enter a query above, then press Enter.", color = TuiPalette.muted)
            if (profileActions && bottom - top >= 5) {
                canvas.text(2, top + 4, "Already have a profile? Tab, then s to review sync.", color = TuiPalette.muted)
            }
        }
    } else {
        val sideBySide = width >= 80
        val divider = (width * 2 / 5).coerceAtLeast(30)
        val listWidth = if (sideBySide) divider - 3 else width - 4
        val availableRows = (bottom - top - 1).coerceAtLeast(1)
        val listRows = if (sideBySide) availableRows else (availableRows / 3).coerceIn(1, 3)
        val first = (state.selection - listRows + 1).coerceAtLeast(0)
        canvas.text(
            2,
            top,
            "MODS  ${state.selection + 1}/${page.mods.size}",
            listWidth,
            if (editing) TuiPalette.muted else TuiPalette.accent,
            bold = true,
        )
        page.mods.drop(first).take(listRows).forEachIndexed { index, mod ->
            val selected = first + index == state.selection
            val background = if (selected && !editing) TuiPalette.accent else TuiPalette.background
            val color = if (selected && !editing) TuiPalette.background else TuiPalette.text
            canvas.fill(1, top + 1 + index, listWidth + 1, 1, background)
            canvas.text(
                2,
                top + 1 + index,
                "${if (selected) "›" else " "} ${mod.name}",
                listWidth,
                color,
                background,
                selected,
            )
        }
        if (sideBySide) {
            for (y in top until bottom) canvas.text(divider, y, "│", color = TuiPalette.border)
        }
        val detailX = if (sideBySide) divider + 3 else 2
        val detailY = if (sideBySide) top else top + listRows + 2
        val detailWidth = width - detailX - 2
        state.selected?.let { mod ->
            val details = listOf(
                mod.name to TuiPalette.accent,
                mod.summary to TuiPalette.text,
                "" to TuiPalette.text,
                "Version  ${mod.version ?: "No published version"}" to TuiPalette.text,
                "Author   ${mod.author}" to TuiPalette.muted,
                "${mod.id.provider}:${mod.id.value}" to TuiPalette.muted,
                mod.pageUrl to TuiPalette.muted,
            )
            val wrapped = details.flatMap { (text, color) ->
                TerminalTextUtils.getWordWrappedText(detailWidth, plainText(text)).map { it to color }
            }
            wrapped.take((bottom - detailY).coerceAtLeast(0)).forEachIndexed { index, (text, color) ->
                canvas.text(detailX, detailY + index, text, detailWidth, color, bold = index == 0)
            }
        }
    }
    val primary = when {
        editing -> "Enter search   ↓/Tab browse"
        profileActions && width >= 80 -> "/ search   ↑↓ select   Enter details   a add   r remove   s sync"
        profileActions -> "Enter details   a add   s sync"
        else -> "/ search   ↑↓ select   Enter details"
    }
    val secondary = if (editing) "Esc browse   Ctrl+C quit" else "? keys   PgUp/PgDn pages   q quit"
    canvas.footer(primary, secondary)
    screen.refresh()
}

private fun renderSearchHelp(screen: Screen, profileActions: Boolean) {
    val canvas = ScreenCanvas(screen)
    if (canvas.tooSmall()) return

    canvas.header("Keyboard controls", "QUICK REFERENCE")
    val lines = buildList {
        add("/ Tab Esc  Focus search / results")
        add("Enter      Search / open details")
        add("↑↓ / j/k   Select · d full details")
        add("PgUp/PgDn  Previous / next page")
        if (profileActions) {
            add("a / r      Add / remove mod")
            add("u / o      Update / check outdated")
            add("s / l      Sync / resolve lock")
        }
    }
    lines.take(canvas.height - 5).forEachIndexed { index, line ->
        canvas.text(2, 2 + index, line, canvas.width - 4)
    }
    canvas.footer("Esc or ? return", "q quit")
    screen.refresh()
}

private fun failureMessage(failure: SearchFailure): String = when (failure) {
    is SearchFailure.UnsupportedGame -> "Unsupported game. Search core-keeper or valheim."
    SearchFailure.EmptyQuery -> "Enter a search query."
    SearchFailure.InvalidOffset -> "Invalid result offset. Start a new search."
    is SearchFailure.Provider -> providerFailureMessage(failure.failure, failure.provider)
}
