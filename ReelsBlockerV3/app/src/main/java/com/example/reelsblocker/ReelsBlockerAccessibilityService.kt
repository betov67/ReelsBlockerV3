package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService — v3 (streamlined rewrite)
 *
 * WHY THIS IS A REWRITE, NOT A PATCH
 * -----------------------------------
 * v2 tried to survive ad-Reel content swaps with a cross-event "origin
 * latch", and gated every TYPE_WINDOW_CONTENT_CHANGED event behind a
 * 300ms debounce Handler. In practice that combination caused the exact
 * failures it was meant to prevent:
 *   - the debounce delayed evaluation just long enough to let a blocked
 *     Reel render a frame or two before BACK fired, which read as lag/
 *     freezing on slower devices;
 *   - the latch's job was to survive "the wrong kind" of screen change,
 *     but it had no reliable way to tell an ad-Reel swap (same session,
 *     keep the latch) apart from a genuine navigation (new session, drop
 *     the latch) — so it occasionally kept a FEED session's block state
 *     from re-asserting itself, letting Reels scroll freely, or dropped a
 *     legitimate DM/Profile allowance it should have kept.
 *
 * v3 removes both mechanisms completely:
 *   - Zero custom timers anywhere in this file. Every event is evaluated
 *     the instant it arrives.
 *   - No ad-aware latch. A Reel session's origin (allowed vs. blocked) is
 *     decided EXACTLY ONCE: the moment a Reel viewer is first detected
 *     after a real, non-Reel screen. For as long as THAT SAME Reel-viewer
 *     window stays on screen — including through ad-Reel content swaps,
 *     caption updates, like-button animations, whatever — the decision is
 *     never revisited. It is only re-decided the next time a genuinely
 *     new Reel-viewer window appears after the user has actually left to
 *     a different screen. This is simpler than a latch and cannot drift:
 *     there is nothing to "survive", because nothing is re-evaluated
 *     while the session hasn't changed.
 *   - The only cooldown left in the whole file guards the BACK action
 *     itself (firing performGlobalAction twice in a few milliseconds can
 *     over-navigate the user straight out of Instagram) — never gates
 *     evaluation.
 *   - Tree lookups use AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId(),
 *     the platform's own indexed search, instead of a hand-rolled
 *     recursive DFS. It's faster and it's the actual reason v2 felt it
 *     needed a debounce in the first place.
 *
 * RULES IMPLEMENTED
 * ------------------
 *  1. Bottom-nav Reels tab tap             -> BACK, immediately (checked
 *                                              straight off the click
 *                                              event's own id — no tree
 *                                              walk at all).
 *  2. Reel opened from the main/home feed  -> BACK, immediately.
 *  3. Reel opened from a user profile      -> ALLOWED (first Reel only).
 *  4. Reel opened from Direct Messages     -> ALLOWED (first Reel only).
 *  5. Any vertical swipe/scroll inside a Reel that was allowed under
 *     rule 3 or 4 — i.e. trying to move on to the NEXT Reel —
 *                                           -> BACK, immediately, and the
 *                                              session is locked down.
 *  6. Stories are 100% exempt. They are checked FIRST, before anything
 *     else, because Instagram visibly reuses Reel-viewer-level containers
 *     for Stories — that sharing is exactly what caused v1/v2's "Stories
 *     get blocked" bug, so Story detection never depends on the same ids
 *     used to detect a Reel.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    private enum class ScreenType { STORY, REEL_VIEWER, DM_THREAD, PROFILE, OTHER }

    companion object {
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Minimum gap between two performGlobalAction(BACK) calls. */
        private const val BACK_ACTION_COOLDOWN_MS = 600L

        // ── Story exclusive signals — checked BEFORE the Reel-viewer check ──
        // These are UI elements that only ever exist inside a Story (the
        // reply box, the segmented progress bar, the stories tray/header).
        // "ReelViewer"-style class names are deliberately never used as a
        // signal here, because Instagram uses that same container class for
        // real Reels too.
        private val STORY_EXCLUSIVE_IDS = setOf(
            "story_reply_composer", "reply_composer", "story_progress_bar",
            "story_duration_bar", "story_progress_container", "stories_tray",
            "reel_viewer_segmented_progress_bar", "story_viewer_swipe_container",
            "story_viewer_reply_box", "quick_reaction_emoji_picker",
            "stories_header", "story_viewer_fragment_container"
        )

        // ── The Reel player itself ── feed Reels, ad Reels, the Reels tab,
        // and Reels opened from a profile or DM link all land on one of
        // these. Deliberately broad — a false positive just means we run
        // the (cheap) origin check again; a false negative lets a Reel
        // through unblocked, which is the worse failure mode.
        private val REEL_VIEWER_IDS = setOf(
            "clips_viewer_view_pager", "clips_swipe_refresh_container",
            "reel_viewer_root", "unified_reel_view", "clips_video_container",
            "clips_fragment_root", "reels_viewer_fragment_root",
            "clips_caption_text_view", "reel_viewer_like_button",
            "clips_tab_fragment_container", "reel_viewer_pager",
            "ad_unit_root", "sponsored_reel_root"
        )

        // ── Bottom navigation bar's Reels/Clips tab button ──
        private val REELS_TAB_IDS = setOf(
            "clips_tab", "tab_icon_clips", "reels_tab", "tab_clips",
            "bottom_nav_clips", "bottom_navigation_clips"
        )

        // ── A DM thread (conversation view) ── one of the two valid
        // origins for an allowed Reel.
        private val DM_THREAD_IDS = setOf(
            "direct_thread_fragment", "direct_thread_recycler_view",
            "thread_view_messages_recycler_view", "row_thread_composer_edittext",
            "direct_thread_toolbar", "direct_text_message_text_view"
        )

        // ── A user's profile page ── the other valid origin for an
        // allowed Reel.
        private val PROFILE_IDS = setOf(
            "profile_header", "profile_tab_layout", "action_bar_profile_name",
            "profile_coordinator_layout", "user_detail_header",
            "profile_header_bio_text"
        )

        // Only structural tree changes can possibly mean a Reel viewer just
        // appeared or disappeared. Filtering on this bitmask is a single
        // integer AND — not a timer — so it costs nothing per event and
        // still screens out the high-frequency noise (progress ticks,
        // caption redraws, like-count updates) that used to need a 300ms
        // debounce to survive.
        private val STRUCTURAL_CHANGE_MASK: Int = run {
            var mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
            }
            mask
        }
    }

    // ── Mutable state ── AccessibilityService callbacks run on the main
    // thread, so this doesn't strictly need @Volatile, but it costs
    // nothing and keeps the fail-safe spirit of the file.

    /** The last screen we saw that was NOT a Reel viewer. Decides origin. */
    @Volatile private var lastNonReelScreenType: ScreenType = ScreenType.OTHER

    /** True while the currently-visible window is an active Reel session. */
    @Volatile private var inReelSession = false

    /** True only if THIS Reel session was opened from a DM thread or profile. */
    @Volatile private var reelOriginAllowed = false

    @Volatile private var lastBackActionMs = 0L

    // ─── Lifecycle ───────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Clean slate every time the service (re)connects.
        lastNonReelScreenType = ScreenType.OTHER
        inReelSession = false
        reelOriginAllowed = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                evaluateCurrentScreen()

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if ((event.contentChangeTypes and STRUCTURAL_CHANGE_MASK) != 0) {
                    evaluateCurrentScreen()
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll()

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)

            else -> { /* not relevant to any rule */ }
        }
    }

    override fun onInterrupt() { /* no-op */ }

    // ─── Core evaluation — runs on window-state and structural content changes ──

    private fun evaluateCurrentScreen() {
        val root = rootInActiveWindow ?: return
        val screenType = classifyScreen(root)

        when (screenType) {
            ScreenType.STORY -> {
                // Fully exempt. lastNonReelScreenType is deliberately left
                // untouched so a Story opened from, say, the DM inbox never
                // gets mistaken for a valid Reel-origin screen.
                inReelSession = false
                reelOriginAllowed = false
            }

            ScreenType.REEL_VIEWER -> {
                if (!inReelSession) {
                    // Brand-new Reel session — decide its origin exactly once.
                    inReelSession = true
                    reelOriginAllowed =
                        lastNonReelScreenType == ScreenType.DM_THREAD ||
                        lastNonReelScreenType == ScreenType.PROFILE
                }
                // Ad-Reel swaps, caption changes, like-button animations all
                // re-fire this with the SAME session still active. Because
                // reelOriginAllowed is only ever set above (on a brand-new
                // session), an allowed Reel stays allowed and a blocked one
                // stays blocked straight through them — no latch needed.
                if (!reelOriginAllowed) fireBack()
            }

            else -> {
                // DM_THREAD, PROFILE, or OTHER: a genuine non-Reel screen.
                // Remember it for the next Reel's origin check, and close
                // out any session that was open.
                lastNonReelScreenType = screenType
                inReelSession = false
                reelOriginAllowed = false
            }
        }
    }

    /** The only way to move to the NEXT Reel — this is what rule 5 blocks. */
    private fun handleScroll() {
        val root = rootInActiveWindow ?: return
        val type = classifyScreen(root)

        if (type == ScreenType.STORY) return   // never touch Stories
        if (type != ScreenType.REEL_VIEWER) return // scrolling somewhere else

        if (!inReelSession) {
            // Inside a Reel viewer but we never got a window-state/content
            // event for it (some OEM fragment-swap paths skip both). Fail
            // safe: treat it as an un-allowed session.
            inReelSession = true
            reelOriginAllowed = false
        }

        if (reelOriginAllowed) {
            // First swipe away from an allowed DM/Profile Reel — the user
            // is trying to move on to the next Reel. Lock the session.
            reelOriginAllowed = false
        }
        fireBack()
    }

    /** Taps — primarily the bottom-nav Reels tab. */
    private fun handleClick(event: AccessibilityEvent) {
        val clickedId = event.source?.viewIdResourceName
        if (clickedId != null && containsIdFragment(clickedId, REELS_TAB_IDS)) {
            // Fastest possible path: no tree fetch, just the clicked
            // view's own id. Immediate back on tab entry (rule 1).
            fireBack()
            return
        }

        // Defensive fallback only when we're not already tracking a
        // session: some Reel entry points (an inline thumbnail in the
        // feed) register only as a click with no window-state transition.
        // Skipping this once inReelSession is true avoids re-running a
        // classification on every like/comment tap inside an open Reel.
        if (!inReelSession) evaluateCurrentScreen()
    }

    // ─── Screen classification ──────────────────────────────────────────

    private fun classifyScreen(root: AccessibilityNodeInfo): ScreenType {
        if (hasAnyId(root, STORY_EXCLUSIVE_IDS)) return ScreenType.STORY
        if (hasAnyId(root, REEL_VIEWER_IDS)) return ScreenType.REEL_VIEWER
        if (hasAnyId(root, DM_THREAD_IDS)) return ScreenType.DM_THREAD
        if (hasAnyId(root, PROFILE_IDS)) return ScreenType.PROFILE
        return ScreenType.OTHER
    }

    /**
     * Uses the platform's own indexed id lookup instead of a manual
     * recursive tree walk. Short-circuits on the first match, so a hit is
     * typically a single native call.
     */
    private fun hasAnyId(root: AccessibilityNodeInfo, shortIds: Set<String>): Boolean {
        for (shortId in shortIds) {
            val fullId = "$INSTAGRAM_PACKAGE:id/$shortId"
            if (root.findAccessibilityNodeInfosByViewId(fullId)?.isNotEmpty() == true) {
                return true
            }
        }
        return false
    }

    private fun containsIdFragment(fullId: String, shortIds: Set<String>): Boolean {
        val shortName = fullId.substringAfterLast('/')
        return shortIds.any { it.equals(shortName, ignoreCase = true) }
    }

    // ─── Back action ──────────────────────────────────────────────────────

    private fun fireBack() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackActionMs < BACK_ACTION_COOLDOWN_MS) return
        lastBackActionMs = now
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
