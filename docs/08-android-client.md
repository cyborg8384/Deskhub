# 08 — Android Client

The Android app (`client/android/`) is a client-only viewer and controller: it connects to a
Deskhub host, decodes the H.264 stream with the device's hardware decoder, and sends mouse and
keyboard input back. It reuses the shared protocol/session code in `core/` unchanged (see
01-architecture.md); everything Android-specific is a thin Kotlin UI plus a small C++ layer.
The Windows client described in 03-client.md is the reference implementation this port follows.

## Architecture

Three layers, one crossing point:

```
MainActivity / StreamActivity (Kotlin, Jetpack Compose)
        │  every native call goes through this single object
NativeClient.kt  ──JNI──  JniBridge.cpp
        │
ClientLoop (C++)  →  core/ (ClientSession, Reassembler, Wire, KeyMap, LinkStats)
        ├── net/UdpSocket, net/SourceQuery
        └── decode/MediaCodecDecoder
```

- `app/src/main/java/com/deskhub/app/NativeClient.kt` is the only Kotlin file allowed to declare
  `external fun`s. JNI binds by string at runtime, so the names must match
  `Java_com_deskhub_app_NativeClient_*` in `app/src/main/cpp/JniBridge.cpp` exactly — a mismatch
  compiles fine and dies with `UnsatisfiedLinkError`.
- `JniBridge.cpp` is deliberately thin: type conversion plus lifetime of one global
  `std::unique_ptr<ClientLoop>` (`g_client`) and one held `ANativeWindow*` (`g_window`). A global
  session generation counter (`g_generation`, returned by `nativeStart`) lets a late `onDestroy`
  of an old `StreamActivity` avoid killing a session a new activity just opened.
- `app/src/main/cpp/ClientLoop.{h,cpp}` is the C++ heart, a close port of
  `client/windows/ClientLoop.cpp`. It wires `UdpSocket`, `deskhub::ClientSession`,
  `deskhub::Reassembler`, and `MediaCodecDecoder` into one session.

### Threads

- **Main (UI) thread** — all Compose UI; calls `Start`/`Stop`/`SetWindow`, polls status every
  500 ms (a `LaunchedEffect` in `StreamActivity.StreamScreen`) instead of C++ calling back into
  the JVM. `NativeClient.listSources` is a `suspend fun` that hops to `Dispatchers.IO` because
  the underlying `nativeListSources` blocks up to ~3 s.
- **Net thread** (`ClientLoop::NetThread`) — `recvfrom` with a 10 ms timeout; video packets go
  straight into the `Reassembler` (bypassing `ClientSession` on the hot path), everything else
  through `ClientSession::HandlePacket`; drains the input queue, runs `session.Tick`, sends
  FEEDBACK/NACK, closes per-second stat windows.
- **Decode thread** (`ClientLoop::DecodeThread`) — pops reassembled frames from a bounded queue
  (`kMaxQueuedFrames = 3`, oldest frame dropped on overflow so the Net thread never blocks),
  feeds `MediaCodecDecoder`, and services Surface handoffs.

Surface handoff is the one place a thread blocks on another: `ClientLoop::SetWindow` bumps a
generation counter (`winGen_`) and waits until the Decode thread acknowledges (`winAckGen_`)
that the codec released the old `ANativeWindow` — destroying a Surface the codec still renders
into is a use-after-free. A `decodeExited_` flag is the anti-hang escape hatch.

## Build system

- `client/android/settings.gradle.kts` — single `:app` module, project `DeskhubAndroid`.
- `client/android/build.gradle.kts` — AGP 9.3.1 (Kotlin is built into AGP 9; no separate
  `kotlin.android` plugin) plus the `org.jetbrains.kotlin.plugin.compose` compiler plugin.
- `client/android/app/build.gradle.kts` — `namespace com.deskhub.app` (fixed: JNI symbol names
  depend on it), `applicationId` defaults to `com.manhpham.deskhub` but is injected by fastlane
  via `-PapplicationId`/`-PversionName`/`-PversionCode` for releases (see 13-release-mobile.md);
  `minSdk 24`, `targetSdk 36`, `compileSdk 37`, NDK 26.1, ABIs `arm64-v8a` + `x86_64`,
  `-DANDROID_STL=c++_static` (the app ships exactly one `.so`).
- `app/src/main/cpp/CMakeLists.txt` — invoked by Gradle/NDK, builds `libdeskhub.so`. It walks
  six directories up to the repo root and `add_subdirectory`s `core/` and `platform/` directly,
  so the shared C++20 code is compiled by the NDK toolchain with no copies. It also links
  `android`, `mediandk`, `log`, and passes `-Wl,-z,max-page-size=16384` so the library loads on
  16 KB-page devices (NDK r26 still aligns to 4 KB by default).
- `make/android.mk` — `make build-android` (`gradlew assembleDebug`), `make release-android`
  (`assembleRelease`), `make run-android` (`installDebug` + `adb shell am start`). Building
  needs only the SDK, no device.
- **Signing**: release builds are signed only when the `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/
  `KEY_ALIAS`/`KEY_PASSWORD` environment variables are set (CI/fastlane); without them
  `release-android` produces an unsigned APK. Debug builds use the default debug key.

## Connect flow

`MainActivity` models the flow as a `sealed interface Step`: `Address` → `Querying` →
`Picking`. There is no network discovery — the user types a bare **IP address** (the port is
the fixed constant `kDeskhubPort` = 47777 in `cpp/net/UdpSocket.h`; `ParseNetAddr` rejects any
string containing `:`). The last address typed is written to `SharedPreferences` and pre-filled
next launch; that is the only memory left, since the **Recents** list (`ui/Recents.kt`, up to 12
machines with LAN/Tailscale labels) was deleted 2026-07-27 along with the on-screen help text.

Connect triggers `NativeClient.listSources`, which runs `QuerySources`
(`cpp/net/SourceQuery.cpp`): a pre-session UDP exchange that resends LIST_SOURCES every 500 ms
for up to 3 s and accepts the first SOURCE_LIST from the queried host (see 04-protocol.md).
Zero or one source skips the picker (old hosts don't know LIST_SOURCES); multiple sources show
`SourcePickerScreen` with radio-style rows. A `seq` on `Step.Querying` discards results from a
stale, non-cancellable query after Back + reconnect.

`StreamActivity` is then started with `addr` + `source` extras and calls
`NativeClient.nativeStart(addr, sourceId)` — the `clientId` is random per session inside the
native layer. The **whole source list** rides along as four parallel arrays
(`srcIds`/`srcW`/`srcH`/`srcNames`), which is what lets the stream screen switch displays
without a second 3-second query.

**Switching display mid-session** (added 2026-07-27, now that hosts share every display): a
`Display` button in the bottom bar — shown only when the host published more than one source —
opens a radio dialog and calls `StreamActivity.switchSource`. The protocol has no "change
source" message and does not need one: each (client, source) pair is already an independent
session, so switching is `nativeStop` + `nativeStart` with a different `sourceId`. The
`SurfaceView` is *not* recreated — `JniBridge` holds `g_window` independently of session
lifetime and `nativeStart` re-attaches it — so the swap costs one handshake and no black flash.
`StreamScreen` keys its polling `LaunchedEffect` on the session generation so stats and any
end-reason from the closed session are cleared rather than lingering. There is no password step: the auth layer was removed project-wide on
2026-07-27 (trusted-LAN decision, see 15-review-todo.md §A1).

A debug-only shortcut: `am start ... --es addr 10.0.2.2` opens `StreamActivity` directly
(guarded by `FLAG_DEBUGGABLE` because `MainActivity` is exported).

## Streaming path

UDP datagram → `Reassembler` (fragment/FEC reassembly, loss accounting) → frame queue →
`MediaCodecDecoder` → SurfaceView. Video pixels never touch Compose or the JVM.

`decode/MediaCodecDecoder.cpp` configures an `AMediaCodec` H.264 decoder directly with the
`ANativeWindow`, so `AMediaCodec_releaseOutputBuffer(..., true)` *is* the render — zero-copy
through the hardware composer (the reason `StreamActivity` uses `SurfaceView`, not
`TextureView`). The `"low-latency"` format key is set as a string so the `.so` still loads
before API 30. On the first frame after each codec (re)build, SPS/PPS preceding the first VCL
NAL are submitted separately under `BUFFER_FLAG_CODEC_CONFIG` (`FirstVclOffset`) for decoders
that require it. `Decode()` returning false means "codec broken": the Decode thread shuts it
down, sets `decodeFailed_`, and the Net thread requests an IDR.

Reconfiguration: the `onReconfig` callback stores the new negotiated size and sets
`rebuildDecoder_` — unlike the Windows `MfDecoder`, MediaCodec is torn down and rebuilt on a
resolution change (the host sends an IDR alongside, so nothing is lost). All keyframe-request
paths (reassembler loss, `WaitingForIdr`, decode failure, queue overflow) funnel into
`session.RequestKeyframe()` with `[DIAG]` logging per 09-diagnostics.md.

Stats surfaced to the UI: `nativeStatusLine` returns the one-per-second line built in
`ClientLoop::NetThread` (`fps / Mbps / loss % / RTT / e2e`), printed as a plain line of text in
the status bar above the video. The stream screen is three stacked rows — status bar, video
(`weight(1f)`), button bar — since 2026-07-27; the bars used to float on top of the video and
now sit outside it. (It used to also be parsed for RTT and drawn as a 60-sample
sparkline; that went with the design system on 2026-07-27 — the numbers are still all there in
the line itself.) `nativeVideoWidth/Height` drive the letterbox aspect ratio. Full per-second stats and `[DIAG]`
events go to logcat, tag `Deskhub` (`cpp/Log.h`; `adb logcat -s Deskhub`).

## Input

All input funnels through `NativeClient`: the raw `external` functions stay private and the
public wrappers (`keyTap`, `keyChord`, `mouseMove`, `mouseButton`, `charTap`, `mouseMoveRel`)
are the only door down to JNI. They no longer gate anything — the view-only checkbox and
`NativeClient.viewOnly` were removed 2026-07-27; the single door is kept so any future rule
still has exactly one place to live. On the C++ side, `ClientLoop::Queue*` methods push `deskhub::InputEvent`s into `inputQueue_`
under a mutex; the Net thread drains the batch each loop into `ClientSession::QueueInput`,
which sequences and redundantly sends them via the core `InputSender` (see 07-input.md), and
calls `SetFocused(true)` once any input has been sent. (The host no longer raises anything on
`SET_FOCUS(true)` — that went with window sharing, removed 2026-07-27; only the `false` edge
matters, releasing held keys.)

- **Trackpad** (`TrackpadOverlay` in `StreamActivity.kt`) — laptop-touchpad semantics: an
  always-visible drawn cursor (`CursorArrow`) moves by *delta*, never jumps to the touch point.
  Tap = left click at the cursor, double-tap = right click, long-press-then-drag = left-button
  drag (mutually exclusive with plain drags by construction). The overlay fills the middle row
  of the screen — letterbox included — but not the status/button bars above and below it, so a
  finger landing on a button no longer jogs the cursor. It is mounted whenever the session is
  streaming.

  Since the zoom work (2026-07-29) the cursor is stored in **content space** — a 0..1 point on
  the *host's* screen — and the on-screen position is derived through `VideoTransform`. What
  is sent is simply `cursor × 65535`, so changing zoom or pan never nudges the host pointer,
  and a finger delta divided by the *zoomed* frame width makes the cursor move slower the
  further you zoom in (the precision mode zooming exists to provide). A move is still re-sent
  immediately before every click so the click lands under the drawn cursor.
- **Virtual keyboard** (`KeyInputView.kt`) — an invisible 1 dp view that holds IME focus and
  captures both input paths: `commitText`/`deleteSurroundingText` on a dummy
  `BaseInputConnection` (Gboard-style IMEs) and raw `onKeyDown` (physical/Bluetooth keyboards).
  `VISIBLE_PASSWORD + NO_SUGGESTIONS` forces per-key commits with no composition. Each
  codepoint goes through `nativeCharTap` → `QueueCharTap`, where core `CharToKeyChord`
  (US layout) expands it into `[Shift↓] key↓ key↑ [Shift↑]`; non-ASCII characters are silently
  dropped.
- **Pinch zoom / pan** (`VideoZoom.kt`, added 2026-07-29) — two fingers pinch to magnify the
  decoded frame (1× fit … 5×) and drag to move the viewport; a `Fit 2.3×` button in the bottom
  bar appears only while zoomed and returns to fit. Nothing is sent to the host: this is purely
  client-side magnification of pixels already received, since the agent always streams the
  source at its native resolution (`AgentLoop` builds the offer straight from the source size
  and ignores `HELLO.maxWidth/maxHeight`). It costs nothing either — the Surface buffer stays
  at video resolution, only the destination rect grows, so the hardware composer does the
  scaling.
  - **One transform, two consumers.** `VideoTransform` holds `zoom`/`pan` and is read by both
    the video frame and the trackpad; letting each compute its own rect would desync them the
    moment zoom is non-1. The middle row measures the viewport once and feeds it in.
  - **`VideoSurfaceHost`** is a real `FrameLayout` with `clipChildren`, because a zoomed
    `SurfaceView` is *larger* than the middle row and Compose's `clipToBounds` cannot clip it —
    the surface is a hole punched in the window by the View system, not something Compose
    draws. It positions the `SurfaceView` by real layout (size + margins), not `scaleX`, since
    the surface geometry is derived from the view's layout position.
  - **Gesture arbitration.** The two-finger handler is a hand-written `awaitEachGesture` loop
    that consumes only when ≥2 pointers are down; `detectTransformGestures` could not be used
    because it consumes single-finger movement past touch slop, which would eat the cursor
    drag. A first `pointerInput` counts pointers on the `Initial` pass and latches
    `multiTouch` until a *new* gesture starts, so the lift that ends a pinch cannot be read as
    a tap.
  - **Cursor and viewport follow each other.** Moving the cursor near the edge auto-pans
    (`ensureVisible`) — without it, zoomed-in regions of the host screen would be unreachable.
    Panning by hand does the opposite (`clampToVisible`): the viewport is what the user asked
    for, so the cursor gets pushed by the edge instead of yanking the view back.
- **Hotkey row** — the `kHotkeys` list in `StreamActivity.kt` (Esc, Tab, Enter, arrows, Del,
  Ctrl+C, Ctrl+V) sends Windows virtual-key codes + scancodes (bit 8 = E0 flag) via
  `keyTap`/`keyChord`. Alt+Tab and the Win key are intentionally excluded (originally because
  they moved focus off the shared window under the old per-window sharing; still left out as
  rarely useful from a hotkey bar).

Tap releases are scheduled `kTapHoldUs` (50 ms) after the press (`delayedInput_`) so games that
poll the keyboard per frame actually see the key held.

## Lifecycle

- `surfaceCreated` → `nativeSetSurface(holder.surface)`; `surfaceDestroyed` →
  `nativeReleaseSurface(holder.surface)`, which blocks until the decoder lets go and compares
  Surface *identity* so a late callback from an old activity cannot steal the new session's
  window. `FLAG_KEEP_SCREEN_ON` prevents the screen (and therefore the Surface and session)
  from dying mid-view.
- **Backgrounding ends the session**: `StreamActivity.onStop` calls `finish()` unless the
  activity is finishing or changing configuration — the protocol has no pause, and without this
  the Net thread would keep receiving full bitrate invisibly. Rotation survives
  (`configChanges` in `app/src/main/AndroidManifest.xml`).
- `onDestroy` calls `nativeStop(session)` with the generation from `nativeStart`, stopping only
  the session this instance created. There is no automatic reconnect: `PHASE_ENDED` shows
  `EndedOverlay` with `nativeEndReason`, and the user reconnects from `MainActivity`. On
  session end the Net thread sends BYE best-effort so the host frees the slot immediately.

## UI system (`ui/` package)

**There is no `ui/` package any more.** On 2026-07-27 the whole bespoke design system was
deleted in stages, on request: first the language and theme switches (`AppState.kt`,
`Strings.kt` + `tr(key)`, `SunIcon`/`MoonIcon`), then — "trông cơ bản thôi, không cần màu mè" —
`Tokens.kt`, `Components.kt` and the rest of `Icons.kt`, and finally every line of on-screen
help text plus `Recents.kt`.

Both screens now use **stock Material 3** (`MaterialTheme(colorScheme = darkColorScheme())`,
`OutlinedTextField`, `Button`, `OutlinedButton`, `RadioButton`, `CircularProgressIndicator`,
`Text`) with English literals inline. Around 1,400 lines of UI code went away; the Kotlin
files are `MainActivity` (~315), `StreamActivity` (~840), `NativeClient` (~220),
`VideoZoom` (~300, added 2026-07-29 for pinch zoom) and `KeyInputView` (~94).

What was **kept** because it is functional, not decoration: the SurfaceView and its letterbox
(now computed by `VideoTransform.fitRect` instead of `Modifier.aspectRatio`, because the frame
rect also depends on zoom/pan), `TrackpadOverlay` with its drawn `CursorArrow` (delta cursor,
tap / double-tap / long-press-drag), the invisible `KeyInputView` that holds IME focus, and the
horizontally scrolling hotkey row. The RTT sparkline went with the design system — the status
line still shows the same numbers as text.

## Known limitations

- **Relative mouse mode is a stub**: `nativeMouseMoveRel`/`QueueMouseMoveRel` (FPS
  pointer-lock, the Windows client's F9 mode) exist end-to-end but no UI calls them — the Lock
  button was removed.
- Virtual-keyboard typing is limited to US-ASCII; anything `CharToKeyChord` cannot map is
  dropped. No scroll-wheel gesture exists (pinch zoom does, see Input).
- **Zoom is client-side only**: it magnifies frames already decoded, so past ~1:1 with the
  device's pixels it stops recovering detail and starts interpolating (which is why it is
  capped at 5×). Streaming only the visible region — host-side crop, real detail at any
  magnification, less bitrate — would need a new wire message plus a crop stage before NVENC
  and an encoder rebuild per zoom step; deliberately not done. Rationale in `VideoZoom.kt`.
- The zoom gesture is **iOS-less for now**: `TouchInputView.swift` still has no pinch
  (`isMultipleTouchEnabled = false`), so the two mobile clients differ here until it is ported.
- No host discovery (no mDNS/broadcast); the address is typed by hand (the last one is pre-filled).
- One session at a time by design: a single global `ClientLoop` behind JNI.
- No pause/resume — backgrounding terminates the session (see Lifecycle).
- IME auto-dismiss tracking (keyboard button state) requires API 30+; older devices keep the
  button latched until pressed again.
- View-only is enforced client-side only, in `NativeClient`.
- H.264 only (`hello.codecMask = kCodecMaskH264`); no audio path exists in the app.
- `make/android.mk`'s header still says no `signingConfig` exists; the Gradle file has since
  added the env-driven release signing described above.
