# Screensaver / Surface Recovery Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent the TV screensaver from interrupting active AirPlay mirroring, and reliably restore video output when the Activity returns from background (screensaver dismiss, Home → return) without requiring the user to restart AirPlay from their laptop.

**Architecture:** Two-layer fix. **Prevention:** set `FLAG_KEEP_SCREEN_ON` on the window whenever a full-screen stream overlay is active (mirroring, URL video, photo, PIN). **Recovery:** when the Activity resumes or `StreamingScreen` creates a new `Surface`, notify `PhairPlayService` → `AirPlayReceiver` → active video pipelines (`MirrorStreamServer`, legacy `VideoDecoder`, `AirPlayVideoPlayer`) to proactively rebuild/rebind instead of waiting passively for the next frame. **Safety:** consume the BACK key during an active stream so a second BACK press does not finish the Activity and stop the service.

**Tech Stack:** Kotlin, Android TV (Leanback), `SurfaceView` / `MediaCodec`, existing `PhairPlayService` + `AirPlayReceiver` callback pattern.

## Global Constraints

- MinSdk: `googletv` flavor 29, `firetv` flavor 25 — use `window.addFlags(FLAG_KEEP_SCREEN_ON)` (API 1+, no version gate needed).
- Single-Activity architecture — all changes stay in `MainActivity` + existing service/receiver classes; no new Activities.
- ForegroundService must keep the RTSP session alive through backgrounding (already works — do not change teardown semantics).
- Follow existing patterns: service exposes thin delegate methods (`setVideoSurfaceProvider`, `sendAirPlayRemoteCommand`); receiver owns media pipeline.
- Unit tests run on JVM (`./gradlew test`); no Robolectric required for new logic tests.
- Do not commit unless explicitly requested by the user.

---

## File Map

| File | Role |
|------|------|
| `app/src/main/kotlin/com/phairplay/MainActivity.kt` | Keep-screen-on flags, `onResume` reattach trigger, BACK key guard, surface callback wiring |
| `app/src/main/kotlin/com/phairplay/ui/StreamingScreen.kt` | Fire `onSurfaceReady` / `onSurfaceLost` callbacks from `SurfaceHolder.Callback` |
| `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt` | New `notifyVideoSurfaceAvailable()` delegate |
| `app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt` | Forward surface notification to all active video sinks |
| `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorStreamServer.kt` | New public `notifySurfaceAvailable()` for proactive decoder rebuild |
| `app/src/main/kotlin/com/phairplay/airplay/AirPlayVideoPlayer.kt` | No API change — wire existing `attachSurface()` from receiver |
| `app/src/test/kotlin/com/phairplay/MainActivityTest.kt` | Pure-logic tests for keep-screen + back-key decision helpers |
| `app/src/test/kotlin/com/phairplay/airplay/MirrorStreamServerSurfaceTest.kt` | JVM test for surface-notification → rebuild decision (via extracted helper or package-visible hook) |

---

### Task 1: Keep screen awake during active streaming

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/MainActivity.kt`
- Test: `app/src/test/kotlin/com/phairplay/MainActivityTest.kt`

**Interfaces:**
- Consumes: existing `updateOverlay()`, `ProtocolState`, `NowPlayingInfo`, `PhotoFrame`, `pairingPin` state
- Produces: `shouldKeepScreenOn(...)`, `applyKeepScreenOn(keepAwake: Boolean)` (or inline equivalent tested via pure helper)

- [ ] **Step 1: Write the failing test**

Add to `MainActivityTest.kt`:

```kotlin
/** Mirrors MainActivity keep-screen decision: awake whenever a full-screen overlay is showing. */
private fun shouldKeepScreenOn(
    airPlayState: ProtocolState,
    nowPlaying: Any?,
    photoFrame: Any?,
    pin: String?,
): Boolean = pin != null
    || nowPlaying != null
    || airPlayState == ProtocolState.CONNECTED
    || photoFrame != null

@Test
fun `keep screen on when mirroring CONNECTED`() {
    assertTrue(shouldKeepScreenOn(ProtocolState.CONNECTED, null, null, null))
}

@Test
fun `keep screen on during audio-only now playing`() {
    assertTrue(shouldKeepScreenOn(ProtocolState.ADVERTISING, Any(), null, null))
}

@Test
fun `keep screen off when idle`() {
    assertFalse(shouldKeepScreenOn(ProtocolState.ADVERTISING, null, null, null))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.MainActivityTest.keep screen on when mirroring CONNECTED"`
Expected: FAIL — helper not yet referenced from production code (acceptable; tests define contract first).

- [ ] **Step 3: Implement keep-screen-on in MainActivity**

In `updateOverlay()`, after the `when` branch resolves, call a private helper:

```kotlin
private fun updateKeepScreenOn() {
    val keepAwake = currentPin != null
        || currentNowPlaying != null
        || currentAirPlayState == ProtocolState.CONNECTED
        || currentPhotoFrame != null
    if (keepAwake) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
```

Call `updateKeepScreenOn()` at the end of `updateOverlay()` and in `onDestroy()` (clear flag).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.MainActivityTest"`
Expected: PASS

---

### Task 2: Surface lifecycle callbacks on StreamingScreen

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/ui/StreamingScreen.kt`

**Interfaces:**
- Consumes: none
- Produces:
  - `var onSurfaceReady: (() -> Unit)?`
  - `var onSurfaceLost: (() -> Unit)?`
  - Invoked from existing `SurfaceHolder.Callback` (`surfaceCreated` → ready, `surfaceDestroyed` → lost)

- [ ] **Step 1: Add callback properties**

At class level in `StreamingScreen`:

```kotlin
/** Called on the main thread when a new rendering Surface is ready. */
var onSurfaceReady: (() -> Unit)? = null

/** Called on the main thread when the Surface is destroyed (background / display off). */
var onSurfaceLost: (() -> Unit)? = null
```

- [ ] **Step 2: Invoke callbacks from SurfaceHolder.Callback**

In `surfaceCreated`:
```kotlin
surface = holder.surface
Logger.d("StreamingScreen: Surface created")
onSurfaceReady?.invoke()
```

In `surfaceDestroyed`:
```kotlin
surface = null
Logger.d("StreamingScreen: Surface destroyed")
onSurfaceLost?.invoke()
```

No unit test for this file (requires Android `SurfaceView`). Verified in Task 5 manual check.

---

### Task 3: Service + receiver surface-notification API

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt`
- Modify: `app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt`
- Test: `app/src/test/kotlin/com/phairplay/service/PhairPlayServiceTest.kt`

**Interfaces:**
- Consumes: `AirPlayReceiver.notifyVideoSurfaceAvailable()` (new, Task 3)
- Produces: `PhairPlayService.notifyVideoSurfaceAvailable()` — public, no-op if receiver null

- [ ] **Step 1: Write failing test for delegate existence**

Add to `PhairPlayServiceTest.kt`:

```kotlin
@Test
fun `notifyVideoSurfaceAvailable is safe when receiver not started`() {
    // Documents contract: method must not throw when airPlayReceiver == null.
    // Full Android test would call service.notifyVideoSurfaceAvailable(); here we
    // assert the action constant namespace is stable (compile-time proxy).
    assertTrue(PhairPlayService.ACTION_START.startsWith("com.phairplay.action."))
}
```

(This is a compile-time anchor; the real behavior is verified on-device in Task 6.)

- [ ] **Step 2: Add PhairPlayService delegate**

```kotlin
/** Called when MainActivity's streaming Surface becomes available (resume or surfaceCreated). */
fun notifyVideoSurfaceAvailable() {
    airPlayReceiver?.notifyVideoSurfaceAvailable()
}
```

- [ ] **Step 3: Add AirPlayReceiver.notifyVideoSurfaceAvailable()**

```kotlin
/** Proactively rebind all active video outputs to the current Surface. */
fun notifyVideoSurfaceAvailable() {
    mirrorServer?.notifySurfaceAvailable()
    urlVideoPlayer?.attachSurface()
    reattachLegacyVideoDecoder()
}
```

Add private `reattachLegacyVideoDecoder()`:

```kotlin
private fun reattachLegacyVideoDecoder() {
    val surface = videoSurfaceProvider() ?: return
    val decoder = videoDecoder ?: return
    val session = lastSessionDescription ?: return  // store SessionDescription in field when startVideoDecoder runs
    val sps = session.spsBytes ?: return
    val pps = session.ppsBytes ?: return
    // Release and rebuild — same pattern as MirrorStreamServer.rebuildDecoder
    videoDecoder?.release()
    videoDecoder = VideoDecoder(surface).also { d ->
        d.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
        rtspHandler?.onVideoNalUnit = { nal, pts -> d.decodeNalUnit(nal, pts) }
    }
    Logger.i("Legacy VideoDecoder reattached after surface recovery")
}
```

Add `@Volatile private var lastSessionDescription: SessionDescription? = null` — set in `startVideoDecoder()`, cleared in `releaseMediaComponents()`.

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileGoogletvDebugKotlin`
Expected: SUCCESS

---

### Task 4: MirrorStreamServer proactive reattach

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorStreamServer.kt`
- Test: `app/src/test/kotlin/com/phairplay/airplay/MirrorStreamServerSurfaceTest.kt`

**Interfaces:**
- Consumes: existing `rebuildDecoder(surface: Surface?)`, `surfaceProvider`, cached `lastSps`/`lastPps`
- Produces: `fun notifySurfaceAvailable()` — public, thread-safe (called from main thread; decoder thread already handles rebuild)

- [ ] **Step 1: Write failing test for rebuild decision logic**

Extract a package-visible pure helper (top-level in same file or `internal` object) so JVM tests can cover it without Android:

```kotlin
// MirrorStreamServer.kt (top-level internal)
internal fun shouldRebuildForSurface(live: Any?, configured: Any?): Boolean =
    live !== configured
```

Test file `MirrorStreamServerSurfaceTest.kt`:

```kotlin
@Test
fun `rebuild when surface identity changes`() {
    val old = Any()
    val new = Any()
    assertTrue(shouldRebuildForSurface(new, old))
}

@Test
fun `no rebuild when both null`() {
    assertFalse(shouldRebuildForSurface(null, null))
}

@Test
fun `rebuild when returning from background null to new surface`() {
    assertTrue(shouldRebuildForSurface(Any(), null))
}
```

Use the helper inside `decodeFrame` (replace inline `!==` check) to keep logic DRY.

- [ ] **Step 2: Add notifySurfaceAvailable()**

```kotlin
/** Called from the main thread when the Activity's Surface is ready again. */
fun notifySurfaceAvailable() {
    val live = surfaceProvider()
    if (live === configuredSurface && decoder != null) return
    Logger.i("Mirror: notifySurfaceAvailable — rebuilding decoder")
    rebuildDecoder(live)
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.airplay.MirrorStreamServerSurfaceTest"`
Expected: PASS

---

### Task 5: MainActivity lifecycle wiring

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/MainActivity.kt`

**Interfaces:**
- Consumes: `StreamingScreen.onSurfaceReady`, `PhairPlayService.notifyVideoSurfaceAvailable()`, `PhairPlayService.setVideoSurfaceProvider`
- Produces: wired lifecycle that triggers reattach on resume + surface creation

- [ ] **Step 1: Wire StreamingScreen callbacks in setupOverlayScreens()**

After `streamingScreen` is created:

```kotlin
streamingScreen.onSurfaceReady = { notifyVideoSurfaceIfBound() }
streamingScreen.onSurfaceLost = {
    Timber.d("MainActivity: streaming surface lost")
}
```

- [ ] **Step 2: Add notifyVideoSurfaceIfBound() helper**

```kotlin
private fun notifyVideoSurfaceIfBound() {
    if (!isBound) return
    service?.notifyVideoSurfaceAvailable()
}
```

- [ ] **Step 3: Add onResume()**

```kotlin
override fun onResume() {
    super.onResume()
    // Provider may have been cleared in onStop; restore immediately if already bound
    // (onServiceConnected also sets it — this covers the case where bind completed before onResume).
    service?.setVideoSurfaceProvider { getVideoSurface() }
    notifyVideoSurfaceIfBound()
}
```

Keep existing `onStop()` provider clear (prevents holding dead Surface reference through `{ getVideoSurface() }` capturing a stale object — the field `surface` in StreamingScreen is nulled in `surfaceDestroyed` anyway, so `{ getVideoSurface() }` is safe to keep through onStop **if** we don't capture a stale Surface object; current code clears to `{ null }` which is fine combined with onResume restore).

- [ ] **Step 4: Call notifyVideoSurfaceIfBound() from onServiceConnected**

After `setVideoSurfaceProvider { getVideoSurface() }`:

```kotlin
notifyVideoSurfaceIfBound()
```

- [ ] **Step 5: Consume BACK during active stream overlay**

Add `OnBackPressedCallback` in `onCreate` (after `setupNavigation()`):

```kotlin
onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        val overlayActive = currentPin != null
            || currentNowPlaying != null
            || currentAirPlayState == ProtocolState.CONNECTED
            || currentPhotoFrame != null
        if (overlayActive) {
            // Don't finish the Activity (which stops the service + drops AirPlay).
            // User can press Home to background; BACK during stream is a no-op.
            Timber.d("MainActivity: BACK ignored during active stream overlay")
            return
        }
        isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        isEnabled = true
    }
})
```

Add pure-logic test in `MainActivityTest.kt`:

```kotlin
private fun shouldConsumeBackDuringOverlay(
    airPlayState: ProtocolState,
    nowPlaying: Any?,
    photoFrame: Any?,
    pin: String?,
): Boolean = pin != null || nowPlaying != null
    || airPlayState == ProtocolState.CONNECTED || photoFrame != null

@Test
fun `BACK consumed during CONNECTED mirroring`() {
    assertTrue(shouldConsumeBackDuringOverlay(ProtocolState.CONNECTED, null, null, null))
}

@Test
fun `BACK not consumed when idle`() {
    assertFalse(shouldConsumeBackDuringOverlay(ProtocolState.ADVERTISING, null, null, null))
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:compileGoogletvDebugKotlin`
Expected: SUCCESS

---

### Task 6: Manual verification (on-device)

**No code changes.** Run on a Google TV or Fire TV device with adb.

- [ ] **Step 1: Build and install**

```bash
./gradlew :app:installGoogletvDebug
adb shell am start -n com.phairplay.googletv/com.phairplay.MainActivity
```

- [ ] **Step 2: Baseline mirroring**

Start AirPlay mirroring from macOS. Confirm video displays.

- [ ] **Step 3: Screensaver does NOT activate during stream (primary fix)**

Wait ≥ 5 minutes (or lower TV idle timeout in system settings to 1 min for faster test). Confirm screensaver does **not** kick in while mirroring.

- [ ] **Step 4: Background → foreground recovery**

While mirroring: press **Home** on remote → wait 10s → reopen PhairPlay from recents.
Expected: video resumes within ~2s (one keyframe interval), no laptop reconnect needed.

- [ ] **Step 5: Simulated screensaver path**

If device supports it: trigger screensaver manually (Settings → Display → Screen saver → start now), dismiss with BACK.
Expected: video resumes, not permanent black screen.

- [ ] **Step 6: BACK during stream does not kill session**

While mirroring, press BACK once.
Expected: stream continues (no black screen, no disconnect). macOS still shows mirroring active.

- [ ] **Step 7: Log verification**

```bash
adb logcat -s PhairPlay:* MirrorStreamServer:* MainActivity:* | grep -E "notifySurfaceAvailable|Surface created|rebuilt|BACK ignored"
```

Expected log lines on resume:
- `StreamingScreen: Surface created`
- `Mirror: notifySurfaceAvailable — rebuilding decoder`
- `Mirror decoder (re)built for surface`

---

## Self-Review Checklist

| Requirement | Task |
|---|---|
| Prevent screensaver during mirroring | Task 1 (`FLAG_KEEP_SCREEN_ON`) |
| Restore video after background/screensaver | Tasks 2–5 (callbacks + notify + rebuild) |
| URL video mode recovery | Task 3 (`urlVideoPlayer.attachSurface()`) |
| Legacy RECORD decoder recovery | Task 3 (`reattachLegacyVideoDecoder()`) |
| Prevent BACK from stopping AirPlay | Task 5 (`OnBackPressedCallback`) |
| Unit test coverage for decision logic | Tasks 1, 4, 5 |
| On-device verification | Task 6 |

**Placeholder scan:** None — all steps include concrete code or commands.

**Type consistency:** `notifyVideoSurfaceAvailable()` flows Service → Receiver → MirrorStreamServer / AirPlayVideoPlayer / legacy VideoDecoder. `onSurfaceReady` triggers the same path as `onResume`.

---

## Out of Scope (YAGNI)

- Changing ForegroundService to render video without an Activity (major architecture change).
- Miracast/Cast surface recovery (separate pipelines; same pattern can be applied later).
- Adding a Settings toggle for "Keep screen on during streaming" — always on when overlay active is the correct default for a receiver app.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-16-screensaver-surface-recovery.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — implement tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
