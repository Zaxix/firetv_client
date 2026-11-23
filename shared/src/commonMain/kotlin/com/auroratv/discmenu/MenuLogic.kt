package com.auroratv.discmenu

/** What the screen asks its media layer to do. The state machine never touches players itself. */
sealed interface MenuEffect {
    data object StartIntro : MenuEffect
    data object StopIntro : MenuEffect
    /** Start the looping menu video at [fromMs] into the loop. */
    data class StartMainVideo(val fromMs: Long) : MenuEffect
    data object StopMainVideo : MenuEffect
    data class StartMusic(val delayMs: Long) : MenuEffect
    data object StopMusic : MenuEffect
    data class FadeOutMusic(val startAfterMs: Long, val durationMs: Long) : MenuEffect
    /** The transition will start shortly (the activation delay): the media layer can get it ready. */
    data object PrepareTransition : MenuEffect
    data object StartTransition : MenuEffect
    data object StopTransition : MenuEffect
    data object StartLetter : MenuEffect
    data object StopLetter : MenuEffect
    /** A visible "not available" notice (an action the reference never showed, or data the server lacks). */
    data class Gate(val message: String) : MenuEffect
    data object PlayMovie : MenuEffect
    data class PlayChapter(val number: Int) : MenuEffect
    data object PlayLastChapter : MenuEffect
    data class SelectSubtitle(val code: String) : MenuEffect
}

enum class MenuKey { Up, Down, Left, Right, Select, Back, NextPage, PreviousPage }

data class MainActivation(val buttonId: String, val at: Long, val startsAt: Long)
data class PageActivation(val hotspotId: String, val at: Long, val firesAt: Long, val action: String)

sealed interface MenuState {
    data object Idle : MenuState
    /** [startedAt] is null until the media layer reports the intro's first picture ([MenuLogic.introStarted]). */
    data class Intro(val requestedAt: Long, val startedAt: Long? = null) : MenuState
    /** [introFadeUntil]: while the intro is still fading out over the loop (see [IntroVideo.crossfadeMs]). */
    data class Main(
        val enteredAt: Long,
        val selected: String,
        val selectedAt: Long,
        val activation: MainActivation? = null,
        val introFadeUntil: Long? = null,
    ) : MenuState
    data class Transition(val startedAt: Long, val target: String) : MenuState
    data class Page(
        val id: String,
        val enteredAt: Long,
        val selected: String,
        val selectedAt: Long,
        val activation: PageActivation? = null,
    ) : MenuState
    data class Letter(val startedAt: Long, val playbackRequested: Boolean = false) : MenuState
}

/**
 * The disc menu's behaviour as a pure state machine: time comes in as [now] (monotonic milliseconds), effects go out
 * through [drainEffects]. Port of the Electron reconstruction's `menu.ts`, plus the intro, the Play Movie letter,
 * Back handling and skipping, which the first walkthrough didn't show.
 *
 * Input is ignored while an activation animation runs (as observed), except that Select/Back may skip the
 * transition video and the intro.
 *
 * [isActionAvailable] lets the host disable actions the server can't serve (a chapter that doesn't exist); such
 * hotspots are skipped by navigation and refused on Select.
 */
class MenuLogic(
    private val pack: DiscMenuPackDef,
    private val isActionAvailable: (String) -> Boolean = { true },
) {
    var state: MenuState = MenuState.Idle
        private set

    private companion object { const val INTRO_FIRST_PICTURE_TIMEOUT_MS = 6_000L }

    private val effects = ArrayDeque<MenuEffect>()
    private val log = ArrayDeque<String>()
    val history: List<String> get() = log.toList()

    private val mainItems: List<NavItem> = pack.main.buttons.map {
        NavItem(it.id, it.rect.toRect("main.${it.id}").padded(pack.main.hotspotPadPx))
    }

    fun drainEffects(): List<MenuEffect> {
        if (effects.isEmpty()) return emptyList()
        val out = effects.toList(); effects.clear(); return out
    }

    private fun emit(e: MenuEffect) { effects.addLast(e) }
    private fun note(s: String) { log.addLast(s); if (log.size > 200) log.removeFirst() }

    // ---- configuration lookups ------------------------------------------------------------------------------

    fun page(id: String): PageDef = pack.pages[id] ?: error("unknown page '$id'")
    fun mainButton(id: String): MainButtonDef = pack.main.buttons.firstOrNull { it.id == id } ?: error("unknown main button '$id'")
    fun hotspot(pageId: String, id: String): HotspotDef = page(pageId).hotspots.firstOrNull { it.id == id } ?: error("unknown hotspot '$pageId/$id'")

    private fun pageItems(pageId: String): List<NavItem> =
        page(pageId).hotspots.map { NavItem(it.id, (it.rect).toRect("$pageId.${it.id}")) }

    fun itemsFor(state: MenuState = this.state): List<NavItem> = when (state) {
        is MenuState.Main -> mainItems
        is MenuState.Page -> pageItems(state.id)
        else -> emptyList()
    }

    private fun mainActivationDelay(buttonId: String): Long {
        val timing = pack.timing.activationToTransitionMs
        // Not every button's delay was observed; the Languages value is the fallback the pack author chose (see pack notes).
        return timing[buttonId] ?: timing["languages"] ?: error("pack has no activation delay for '$buttonId' and no fallback")
    }

    private fun actionOf(state: MenuState): String? = when (state) {
        is MenuState.Main -> mainButton(state.selected).action
        is MenuState.Page -> hotspot(state.id, state.selected).action
        else -> null
    }

    private fun enabled(pageId: String?, id: String): Boolean {
        val action = if (pageId == null) mainButton(id).action else hotspot(pageId, id).action
        return isActionAvailable(action)
    }

    // ---- entering states ------------------------------------------------------------------------------------

    /** Opens the disc: the intro when the pack has one (and [withIntro]), otherwise straight to the menu. */
    fun start(now: Long, withIntro: Boolean = true) {
        val intro = pack.video.intro
        if (intro != null && withIntro) {
            state = MenuState.Intro(now)
            emit(MenuEffect.StartIntro)
            note("intro @$now")
        } else {
            enterMain(now)
        }
    }

    private fun defaultMainButton(): MainButtonDef =
        pack.main.buttons.firstOrNull { it.default } ?: pack.main.buttons.first()

    private fun enterMain(now: Long, introFadeUntil: Long? = null, loopFromMs: Long = pack.video.loop.entryMs, musicDelayMs: Long = pack.music.startLagAfterVideoEntryMs) {
        state = MenuState.Main(now, defaultMainButton().id, now, introFadeUntil = introFadeUntil)
        emit(MenuEffect.StopMusic)
        emit(MenuEffect.StartMainVideo(loopFromMs))
        emit(MenuEffect.StartMusic(musicDelayMs))
        note("main @$now")
    }

    private fun enterPage(id: String, now: Long, from: String) {
        val p = page(id)
        val sel = p.entryDefaultFrom[from]?.takeIf { wanted -> p.hotspots.any { it.id == wanted } && enabled(id, wanted) } ?: firstEnabled(id, p.default)
        val wasOnAPage = state is MenuState.Page
        state = MenuState.Page(id, now, sel, now)
        if (!wasOnAPage) emit(MenuEffect.StopMainVideo)
        note("page $id (from $from) sel=$sel @$now")
    }

    private fun firstEnabled(pageId: String, preferred: String): String {
        if (enabled(pageId, preferred)) return preferred
        return page(pageId).hotspots.firstOrNull { isActionAvailable(it.action) }?.id ?: preferred
    }

    // ---- input ----------------------------------------------------------------------------------------------

    private fun busy(): Boolean = when (val s = state) {
        is MenuState.Main -> s.activation != null
        is MenuState.Page -> s.activation != null
        is MenuState.Transition, is MenuState.Letter, is MenuState.Intro, MenuState.Idle -> true
    }

    private fun select(id: String, now: Long) {
        when (val s = state) {
            is MenuState.Main -> if (s.selected != id) { state = s.copy(selected = id, selectedAt = now); note("select $id") }
            is MenuState.Page -> if (s.selected != id) { state = s.copy(selected = id, selectedAt = now); note("select ${s.id}/$id") }
            else -> Unit
        }
    }

    /**
     * Handles a key. Returns false only when Back was pressed on the main menu, i.e. the host should leave the menu.
     */
    fun onKey(key: MenuKey, now: Long): Boolean {
        when (val s = state) {
            is MenuState.Intro -> { if (key == MenuKey.Select || key == MenuKey.Back) skipIntro(now); return true }
            is MenuState.Transition -> { if (key == MenuKey.Select || key == MenuKey.Back) skipTransition(s, now); return true }
            is MenuState.Letter -> {
                if (key == MenuKey.Back && !s.playbackRequested) { emit(MenuEffect.StopLetter); emit(MenuEffect.StopMusic); enterMain(now) }
                return true
            }
            MenuState.Idle -> return true
            else -> Unit
        }
        return when (key) {
            MenuKey.Up -> { move(Dir.Up, now); true }
            MenuKey.Down -> { move(Dir.Down, now); true }
            MenuKey.Left -> { move(Dir.Left, now); true }
            MenuKey.Right -> { move(Dir.Right, now); true }
            MenuKey.Select -> { activate(now); true }
            MenuKey.Back -> back(now)
            MenuKey.NextPage -> { stepPage(+1, now); true }
            MenuKey.PreviousPage -> { stepPage(-1, now); true }
        }
    }

    private fun move(dir: Dir, now: Long) {
        if (busy()) return
        when (val s = state) {
            is MenuState.Main -> MenuGeometry.neighbour(s.selected, mainItems, dir) { enabled(null, it) }?.let { select(it, now) }
            is MenuState.Page -> MenuGeometry.neighbour(s.selected, pageItems(s.id), dir) { enabled(s.id, it) }?.let { select(it, now) }
            else -> Unit
        }
    }

    fun hover(x: Float, y: Float, now: Long) {
        if (busy()) return
        val id = hit(x, y) ?: return
        select(id, now)
    }

    /** The focusable item under a canvas point, or null. Items the server can't serve don't count. */
    fun hit(x: Float, y: Float): String? = when (val s = state) {
        is MenuState.Main -> mainItems.firstOrNull { it.rect.contains(x, y) && enabled(null, it.id) }?.id
        is MenuState.Page -> pageItems(s.id).firstOrNull { it.rect.contains(x, y) && enabled(s.id, it.id) }?.id
        else -> null
    }

    /** Mouse or touch press at a canvas point: selects what's under it and activates it. */
    fun click(x: Float, y: Float, now: Long) {
        if (busy()) return
        val id = hit(x, y) ?: return
        select(id, now)
        activate(now)
    }

    private fun back(now: Long): Boolean {
        if (busy()) return true
        return when (val s = state) {
            is MenuState.Main -> false
            is MenuState.Page -> {
                val target = page(s.id).backTo
                if (target == null) enterMain(now) else enterPage(target, now, s.id)
                true
            }
            else -> true
        }
    }

    /** Next / previous sibling page (scene pages, cast and crew). No-op when the page isn't in a sequence or is at its end. */
    private fun stepPage(delta: Int, now: Long) {
        if (busy()) return
        val s = state as? MenuState.Page ?: return
        val seq = pack.sequences.values.firstOrNull { s.id in it } ?: return
        val i = seq.indexOf(s.id) + delta
        if (i in seq.indices) enterPage(seq[i], now, s.id)
    }

    private fun activate(now: Long) {
        if (busy()) return
        when (val s = state) {
            is MenuState.Main -> activateMain(s, now)
            is MenuState.Page -> {
                val h = hotspot(s.id, s.selected)
                if (!isActionAvailable(h.action)) { emit(MenuEffect.Gate("Not available for this title")); return }
                state = s.copy(activation = PageActivation(h.id, now, now + pack.timing.subActivationToCutMs, h.action))
                note("activate ${s.id}/${h.id} -> ${h.action}")
            }
            else -> Unit
        }
    }

    private fun activateMain(s: MenuState.Main, now: Long) {
        val b = mainButton(s.selected)
        if (b.action == "play_movie") {
            val delay = pack.timing.playMovie?.activationToDissolveMs ?: 0L
            state = s.copy(activation = MainActivation(b.id, now, now + delay))
            note("activate play_movie")
            if (delay == 0L) update(now)
            return
        }
        state = s.copy(activation = MainActivation(b.id, now, now + mainActivationDelay(b.id)))
        emit(MenuEffect.StopMusic) // observed: the music is cut at the moment of activation
        emit(MenuEffect.PrepareTransition)
        note("activate ${b.id}")
    }

    private fun skipIntro(now: Long) {
        val intro = pack.video.intro ?: return
        if (!intro.skippable) return
        emit(MenuEffect.StopIntro)
        enterMain(now)
        note("intro skipped")
    }

    private fun skipTransition(s: MenuState.Transition, now: Long) {
        emit(MenuEffect.StopTransition)
        enterPage(s.target, now, "main")
        note("transition skipped")
    }

    // ---- time -----------------------------------------------------------------------------------------------

    fun update(now: Long) {
        when (val s = state) {
            is MenuState.Intro -> updateIntro(s, now)
            is MenuState.Main -> updateMain(s, now)
            is MenuState.Transition -> {
                if (now >= s.startedAt + pack.video.transition.pageCutMs) {
                    emit(MenuEffect.StopTransition)
                    enterPage(s.target, now, "main")
                }
            }
            is MenuState.Page -> updatePage(s, now)
            is MenuState.Letter -> updateLetter(s, now)
            MenuState.Idle -> Unit
        }
    }

    /** The media layer drew the intro's first picture: the intro's clock starts here, so the hand-off lines up with the video itself. */
    fun introStarted(now: Long) {
        val s = state as? MenuState.Intro ?: return
        if (s.startedAt == null) state = s.copy(startedAt = now)
    }

    /** The intro can't be played (missing or undecodable file): open on the menu instead of hanging. */
    fun introFailed(now: Long) {
        if (state !is MenuState.Intro) return
        emit(MenuEffect.StopIntro)
        enterMain(now)
        note("intro failed")
    }

    private fun updateIntro(s: MenuState.Intro, now: Long) {
        val intro = pack.video.intro ?: return
        val startedAt = s.startedAt
        if (startedAt == null) {
            if (now - s.requestedAt > INTRO_FIRST_PICTURE_TIMEOUT_MS) introFailed(now)
            return
        }
        val half = intro.crossfadeMs / 2
        val handOffAt = startedAt + intro.durationMs
        if (now >= handOffAt - half) {
            // The loop starts `half` early (at the matching earlier phase) so it is already running underneath the fading intro;
            // the music starts exactly at the hand-off point.
            val early = maxOf(0L, handOffAt - now)
            val loopFrom = (pack.video.loop.entryMs - early).coerceAtLeast(0L)
            enterMain(
                now,
                introFadeUntil = if (intro.crossfadeMs > 0) handOffAt + half else null,
                loopFromMs = loopFrom,
                musicDelayMs = early + pack.music.startLagAfterVideoEntryMs,
            )
            if (intro.crossfadeMs == 0L) emit(MenuEffect.StopIntro)
            note("intro hand-off")
        }
    }

    private fun updateMain(s: MenuState.Main, now: Long) {
        val fadeEnd = s.introFadeUntil
        var cur = s
        if (fadeEnd != null && now >= fadeEnd) {
            emit(MenuEffect.StopIntro)
            cur = s.copy(introFadeUntil = null)
            state = cur
        }
        val act = cur.activation ?: return
        if (now < act.startsAt) return
        val b = mainButton(act.buttonId)
        if (b.action == "play_movie") {
            if (pack.video.letter == null) {
                state = cur.copy(activation = null)
                emit(MenuEffect.PlayMovie)
                return
            }
            state = MenuState.Letter(now)
            emit(MenuEffect.StartLetter)
            val fade = pack.music.fadeOutOnPlayMovie
            if (fade != null) emit(MenuEffect.FadeOutMusic(fade.startAfterDissolveMs, fade.durationMs)) else emit(MenuEffect.StopMusic)
            note("letter @$now")
            return
        }
        val target = b.action.removePrefix("goto:")
        state = MenuState.Transition(now, target)
        emit(MenuEffect.StopMainVideo)
        emit(MenuEffect.StartTransition)
        note("transition -> $target @$now")
    }

    private fun updatePage(s: MenuState.Page, now: Long) {
        val act = s.activation ?: return
        if (now < act.firesAt) return
        val a = act.action
        when {
            a.startsWith("goto:") -> {
                val t = a.removePrefix("goto:")
                if (t == "main") enterMain(now) else enterPage(t, now, s.id)
            }
            a.startsWith("gate:") -> { state = s.copy(activation = null); emit(MenuEffect.Gate(a.removePrefix("gate:"))) }
            a == "play" -> { state = s.copy(activation = null); emit(MenuEffect.PlayMovie) }
            a == "chapter:last" -> { state = s.copy(activation = null); emit(MenuEffect.PlayLastChapter) }
            a.startsWith("chapter:") -> {
                state = s.copy(activation = null)
                val n = a.removePrefix("chapter:").toIntOrNull()
                if (n == null) emit(MenuEffect.Gate("Bad chapter action '$a' in the pack")) else emit(MenuEffect.PlayChapter(n))
            }
            a.startsWith("subtitle:") -> { state = s.copy(activation = null); emit(MenuEffect.SelectSubtitle(a.removePrefix("subtitle:"))) }
            else -> state = s.copy(activation = null) // "stay"
        }
    }

    private fun updateLetter(s: MenuState.Letter, now: Long) {
        if (s.playbackRequested) return
        val letter = pack.video.letter ?: return
        val hold = pack.timing.playMovie?.letterHoldBeforePlaybackMs ?: 0L
        if (now >= s.startedAt + letter.durationMs + hold) {
            state = s.copy(playbackRequested = true)
            emit(MenuEffect.PlayMovie)
            note("play movie @$now")
        }
    }

    /** Called when the screen is left or paused: stops everything the menu started. */
    fun shutdown() {
        emit(MenuEffect.StopIntro); emit(MenuEffect.StopMainVideo); emit(MenuEffect.StopTransition)
        emit(MenuEffect.StopLetter); emit(MenuEffect.StopMusic)
        state = MenuState.Idle
    }
}
