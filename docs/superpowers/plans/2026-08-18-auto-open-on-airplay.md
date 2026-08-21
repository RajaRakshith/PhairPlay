# Auto-Open PhairPlay on AirPlay Session — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When any AirPlay overlay session starts (mirroring, audio-only, PIN, photo) and PhairPlay is in the background, automatically bring `MainActivity` to the foreground so the user sees video/metadata without manual navigation.

**Architecture:** Rising-edge detection on `OverlaySessionPolicy.isOverlayActive()` inside a small `SessionLaunchHelper`, gated by `AppForegroundTracker` (Activity lifecycle callbacks on `PhairPlayApp`). `PhairPlayService` calls one `refreshOverlaySessionLaunch()` after every overlay-driving StateFlow update. Pure launch decision logic lives in testable `SessionLaunchPolicy`.

**Tech Stack:** Kotlin 1.9.23, Android TV (Leanback), existing `PhairPlayService` + `OverlaySessionPolicy`, JVM unit tests via `:test-runner:test`.

## Global Constraints

- MinSdk: `googletv` 29, `firetv` 25 — no new permissions required.
- Single-Activity architecture — launch existing `MainActivity` only (`launchMode="singleTop"` already set).
- ≤400 lines per Kotlin file — add new files; do not grow `PhairPlayService.kt` materially (already ~437 lines).
- KDoc on every new class; unit test every public method on `SessionLaunchPolicy`.
- CI gate: `./gradlew :test-runner:test`, `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug :app:assembleGoogletvDebug :app:assembleFiretvDebug`.
- Do not commit unless explicitly requested by the user.

---

## File Map

| File | Role |
|------|------|
| `app/src/main/kotlin/com/phairplay/SessionLaunchPolicy.kt` | Pure rising-edge + foreground launch decision |
| `app/src/main/kotlin/com/phairplay/AppForegroundTracker.kt` | Tracks `isInForeground` via lifecycle callbacks |
| `app/src/main/kotlin/com/phairplay/SessionLaunchHelper.kt` | Stateful coordinator; builds Intent; calls `startActivity` |
| `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt` | Creates and exposes `AppForegroundTracker` |
| `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt` | Calls `refreshOverlaySessionLaunch()` after overlay signal updates |
| `app/src/test/kotlin/com/phairplay/SessionLaunchPolicyTest.kt` | JVM tests for policy (CI gate) |

---

### Task 1: SessionLaunchPolicy (pure logic)

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/SessionLaunchPolicy.kt`
- Test: `app/src/test/kotlin/com/phairplay/SessionLaunchPolicyTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `SessionLaunchPolicy.shouldLaunchMainActivity(wasOverlayActive: Boolean, isOverlayActive: Boolean, isAppInForeground: Boolean): Boolean`

- [ ] **Step 1: Write the failing test**

Create `SessionLaunchPolicyTest.kt`:

```kotlin
package com.phairplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLaunchPolicyTest {

    @Test
    fun `launch on rising edge when background`() {
        assertTrue(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = false,
                isOverlayActive = true,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `no launch when already foreground`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = false,
                isOverlayActive = true,
                isAppInForeground = true,
            )
        )
    }

    @Test
    fun `no launch when overlay stays active and user backgrounds mid-stream`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = true,
                isOverlayActive = true,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `no launch when overlay inactive`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = false,
                isOverlayActive = false,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `no launch on falling edge`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = true,
                isOverlayActive = false,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `launch again on new session after teardown`() {
        // Session ended (falling edge — no launch), then new session (rising edge — launch).
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(true, false, false)
        )
        assertTrue(
            SessionLaunchPolicy.shouldLaunchMainActivity(false, true, false)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :test-runner:test --tests "com.phairplay.SessionLaunchPolicyTest"`

Expected: FAIL — `SessionLaunchPolicy` unresolved

- [ ] **Step 3: Write minimal implementation**

Create `SessionLaunchPolicy.kt`:

```kotlin
package com.phairplay

/**
 * SessionLaunchPolicy — decides whether to bring [com.phairplay.MainActivity] to the foreground.
 *
 * WHY: AirPlay sessions need MainActivity's SurfaceView. Launch only on the rising edge into
 * overlay-active while the app is backgrounded — not when the user presses Home mid-stream.
 *
 * Example:
 *   if (SessionLaunchPolicy.shouldLaunchMainActivity(wasActive, isActive, inForeground)) {
 *       sessionLaunchHelper.launchMainActivity()
 *   }
 */
object SessionLaunchPolicy {

    /**
     * True when overlay just became active and the app is not in the foreground.
     *
     * @param wasOverlayActive Previous overlay-active state (before this signal update).
     * @param isOverlayActive  Current overlay-active state from [OverlaySessionPolicy].
     * @param isAppInForeground True while any Activity is started (see [AppForegroundTracker]).
     */
    fun shouldLaunchMainActivity(
        wasOverlayActive: Boolean,
        isOverlayActive: Boolean,
        isAppInForeground: Boolean,
    ): Boolean = isOverlayActive && !wasOverlayActive && !isAppInForeground
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :test-runner:test --tests "com.phairplay.SessionLaunchPolicyTest"`

Expected: PASS

---

### Task 2: AppForegroundTracker

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/AppForegroundTracker.kt`
- Modify: `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt`

**Interfaces:**
- Consumes: Android `Application` lifecycle
- Produces:
  - `AppForegroundTracker.isInForeground: Boolean` (read-only property)

- [ ] **Step 1: Create AppForegroundTracker**

```kotlin
package com.phairplay

import android.app.Activity
import android.app.Application

/**
 * AppForegroundTracker — reports whether any Activity in this process is in the started state.
 *
 * WHY: PhairPlayService must not start MainActivity when the user is already viewing the app
 * (overlay updates are handled by bound Activity Flow collectors).
 */
class AppForegroundTracker(application: Application) {

    @Volatile
    var isInForeground: Boolean = false
        private set

    private var startedActivityCount = 0

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                isInForeground = startedActivityCount > 0
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                isInForeground = startedActivityCount > 0
            }

            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
```

- [ ] **Step 2: Wire into PhairPlayApp**

Add to `PhairPlayApp.kt`:

```kotlin
lateinit var foregroundTracker: AppForegroundTracker
    private set

override fun onCreate() {
    super.onCreate()
    foregroundTracker = AppForegroundTracker(this)
    initLogging()
}
```

- [ ] **Step 3: Build both flavors**

Run: `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug`

Expected: BUILD SUCCESSFUL

---

### Task 3: SessionLaunchHelper

**Files:**
- Create: `app/src/main/kotlin/com/phairplay/SessionLaunchHelper.kt`

**Interfaces:**
- Consumes: `SessionLaunchPolicy.shouldLaunchMainActivity`, `OverlaySessionPolicy.isOverlayActive` inputs, `AppForegroundTracker.isInForeground`
- Produces:
  - `SessionLaunchHelper.onOverlayActiveChanged(isOverlayActive: Boolean): Unit`
  - `SessionLaunchHelper.launchMainActivity(): Unit` (internal/test seam)

- [ ] **Step 1: Create SessionLaunchHelper**

```kotlin
package com.phairplay

import android.content.Context
import android.content.Intent
import com.phairplay.util.Logger

/**
 * SessionLaunchHelper — stateful bridge from PhairPlayService overlay signals to MainActivity launch.
 */
class SessionLaunchHelper(
    private val appContext: Context,
    private val isAppInForeground: () -> Boolean,
    private val launch: (Intent) -> Unit = { intent ->
        appContext.startActivity(intent)
    },
) {
    private var wasOverlayActive = false

    fun onOverlayActiveChanged(isOverlayActive: Boolean) {
        if (SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = wasOverlayActive,
                isOverlayActive = isOverlayActive,
                isAppInForeground = isAppInForeground(),
            )
        ) {
            launchMainActivity()
        }
        wasOverlayActive = isOverlayActive
    }

    internal fun launchMainActivity() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        try {
            launch(intent)
            Logger.i("SessionLaunchHelper: brought MainActivity to foreground for AirPlay session")
        } catch (e: Exception) {
            Logger.w("SessionLaunchHelper: startActivity blocked — ${e.message}")
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleGoogletvDebug`

Expected: BUILD SUCCESSFUL

---

### Task 4: PhairPlayService integration

**Files:**
- Modify: `app/src/main/kotlin/com/phairplay/service/PhairPlayService.kt`

**Interfaces:**
- Consumes: `SessionLaunchHelper`, `OverlaySessionPolicy.isOverlayActive`, `PhairPlayApp.foregroundTracker`
- Produces:
  - `private fun refreshOverlaySessionLaunch()` on `PhairPlayService`

- [ ] **Step 1: Add helper field in PhairPlayService**

In `onCreate()` after `settingsRepository` init:

```kotlin
private lateinit var sessionLaunchHelper: SessionLaunchHelper

override fun onCreate() {
    super.onCreate()
    settingsRepository = SettingsRepository(applicationContext)
    val app = application as PhairPlayApp
    sessionLaunchHelper = SessionLaunchHelper(
        appContext = applicationContext,
        isAppInForeground = { app.foregroundTracker.isInForeground },
    )
    createNotificationChannel()
}
```

- [ ] **Step 2: Add refreshOverlaySessionLaunch()**

```kotlin
private fun refreshOverlaySessionLaunch() {
    val overlayActive = OverlaySessionPolicy.isOverlayActive(
        airPlayState = _airPlayState.value,
        nowPlaying = _nowPlaying.value,
        photoFrame = _photoFrame.value,
        pin = _pairingPin.value,
    )
    sessionLaunchHelper.onOverlayActiveChanged(overlayActive)
}
```

Add import: `com.phairplay.OverlaySessionPolicy`, `com.phairplay.PhairPlayApp`, `com.phairplay.SessionLaunchHelper`.

- [ ] **Step 3: Call after every overlay-driving update**

Add `refreshOverlaySessionLaunch()` at the end of:

1. `onStateChanged` lambda body (after `_airPlayState.value = state` and notification branch)
2. `onNowPlayingChanged` (after `_nowPlaying.value = info`)
3. `onPinChanged` (after `_pairingPin.value = pin`)
4. `onPhotoReceived` (after `_photoFrame.value = ...`)
5. `onPhotoCleared` (after `_photoFrame.value = null`)
6. `stopAllReceiversInternal()` (after clearing `_nowPlaying`, `_pairingPin`, `_photoFrame`, `_airPlayState`)

- [ ] **Step 4: Run CI gate**

Run:

```bash
./gradlew :test-runner:test
./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
  :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

Expected: all PASS

---

### Task 5: Manual verification checklist (real TV — optional)

- [ ] Netflix (or any app) foreground; PhairPlay service advertising; start mirroring → PhairPlay opens with video.
- [ ] Audio-only AirPlay → now-playing overlay visible without manual open.
- [ ] PIN access control enabled → PIN screen appears on sender connect attempt.
- [ ] During active mirroring, press Home → PhairPlay does **not** immediately reopen.
- [ ] Stop mirroring, start again from another app → PhairPlay reopens.

Log filter:

```bash
adb logcat -s PhairPlay:* SessionLaunchHelper:* MainActivity:* | grep -E "brought MainActivity|syncOverlayFromService|startActivity blocked"
```

---

## Spec Self-Review

| Spec requirement | Plan task |
|---|---|
| All four overlay types trigger launch | Task 4 hooks all StateFlow updates |
| Rising edge only | Task 1 `SessionLaunchPolicy` + Task 3 state |
| No relaunch on Home mid-stream | Task 1 test `overlay stays active` |
| Reuse `OverlaySessionPolicy` | Task 4 `refreshOverlaySessionLaunch` |
| Foreground detection | Task 2 |
| BAL failure graceful | Task 3 try/catch + log |
| Unit tests CI gate | Task 1 |
| ≤400 lines / KDoc | New files in Tasks 1–3 |

No placeholders remain.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-18-auto-open-on-airplay.md`. Spec at `docs/superpowers/specs/2026-08-18-auto-open-on-airplay-design.md`.

Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks
2. **Inline Execution** — implement tasks in this session with checkpoints

Which approach do you want?
