# 12 — iOS Client

The iOS app is one of the Deskhub client platforms (see `01-architecture.md`, `03-client.md`).
It is **client-only**: it views and controls a desktop host, never shares its own screen. The
current build streams video *and* sends input — touch-driven mouse, a virtual keyboard, and a
shortcut bar — over the standard UDP protocol described in `04-protocol.md`.

Sources live in `client/ios/`:

- `client/ios/app/swift/` — SwiftUI UI, session state, recents.
- `client/ios/app/cpp/` — C facade, `ClientLoop`, `VtDecoder`, POSIX `UdpSocket`/`SourceQuery`.
- `client/ios/Deskhub.xcodeproj` — a single `app` target; `app/` is a
  `PBXFileSystemSynchronizedRootGroup`, so files added on disk join the build automatically.
- `client/ios/fastlane/` — App Store metadata and lanes (see `13-release-mobile.md`).

## 1. Architecture: four layers

```
SwiftUI views (ConnectView / SourcePickerView / StreamView)
    │  read state, call actions
SessionModel (@MainActor @Observable)          — client/ios/app/swift/SessionModel.swift
    │  the only caller of the facade wrapper
DeskhubClient (Swift enum of static funcs)     — client/ios/app/swift/DeskhubClient.swift
    │  plain C calls via Deskhub-Bridging-Header.h
dh_* C facade (DeskhubClient.h / .mm, ObjC++)  — client/ios/app/cpp/
    │  one global session: std::shared_ptr<ClientLoop> g_client + g_mutex
ClientLoop (pure C++) ── reuses core/: ClientSession, Reassembler, Wire, LinkStats, KeyMap
```

- `DeskhubClient.swift` is the single Swift-side gateway; no view calls C directly. It mirrors
  Android's `NativeClient.kt`, and `DeskhubClient.mm` mirrors `JniBridge.cpp`.
- The facade is flat C functions (not an ObjC class) exported through
  `client/ios/app/swift/Deskhub-Bridging-Header.h`. `DeskhubClient.mm` is ObjC++ only because
  the video layer crosses the boundary as a `__bridge void*`.
- `ClientLoop` (`client/ios/app/cpp/ClientLoop.h/.cpp`) is a close port of the Android
  `ClientLoop`; only the decoder (`VtDecoder` instead of `MediaCodecDecoder`) and the render
  target (`AVSampleBufferDisplayLayer` instead of `Surface`) differ. `UdpSocket` and
  `SourceQuery` under `client/ios/app/cpp/net/` are copied from the Android client (both are
  POSIX). `NetAddr` is IPv4-only, host byte order.

### Threading

Three threads per session, documented in `ClientLoop.h`:

- **Main** — SwiftUI; hands the layer over (`SetLayer`), polls phase/stats every 500 ms via a
  `Timer` in `SessionModel.startPolling()` (added to `RunLoop.main` in `.common` mode so it
  keeps firing during scroll tracking).
- **Net** (`ClientLoop::NetThread`) — `recvfrom` with a 10 ms timeout; video-channel packets go
  straight into `deskhub::Reassembler` (hot path, bypassing `ClientSession` except for
  `NotifyVideoPacket`), everything else through `ClientSession::HandlePacket`. It also drains
  the input queue into `ClientSession`, calls `Tick`, requests
  keyframes, plans NACKs, and closes the 1-second stats window.
- **Decode** (`ClientLoop::DecodeThread`) — pops reassembled frames from a bounded queue
  (`kMaxQueuedFrames = 3`; overflow drops the *oldest* frame and flags an IDR request) and
  feeds `VtDecoder`. Net and Decode are separate so a slow decode never stalls `recvfrom` —
  a stalled socket overflows the kernel UDP buffer and causes real loss.

Layer handover is a generation-counted handshake (`winGen_`/`winAckGen_`): `SetLayer` blocks
the caller until the Decode thread acknowledges it has released the old layer.
`dh_set_layer` deliberately copies the `shared_ptr` and calls `SetLayer` *outside* `g_mutex`
so concurrent `dh_phase`/`dh_stop` calls cannot deadlock. `dh_list_sources` blocks up to ~3 s
and must run off the main thread — Swift wraps it in `Task.detached`.

## 2. Build

`core/` is compiled by a Run Script phase in the Xcode `app` target ("Build libcore.a
(CMake)"): it invokes CMake on `core/CMakeLists.txt` with `CMAKE_SYSTEM_NAME=iOS`, the current
SDK/arch/deployment target (iOS 17.0), and `DESKHUB_CORE_TESTS=OFF`, outputting to
`out/build/ios-core/$PLATFORM_NAME-$CONFIGURATION`. The target links `-lcore` from that
directory; header search paths add `core/include`, `platform/include`, and `app/cpp`. CMake is
the single source of truth for the core file list — Xcode keeps no hand-copied list.

Make targets (`make/ios.mk`, macOS only):

- `make build-ios` — `xcodebuild -target app -configuration Debug -sdk iphonesimulator`,
  products under `out/build/ios/Debug-iphonesimulator/app.app`.
- `make release-ios` — same with `-configuration Release`, still Simulator SDK.
- `make run-ios` — builds, boots the Simulator, installs and launches `com.ios.deskhub`.

Per the comments in `ios.mk`, real-device and App Store builds need a signing team and an
archive through Xcode/fastlane — they are not covered by make. See `13-release-mobile.md`.

**On the Mac.** `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = YES`, so the same binary that
goes to TestFlight also runs on Apple Silicon Macs (Intel Macs cannot run it) and is
listed in the Mac App Store under "iPhone & iPad Apps". There is no Mac target, no
separate product, and no extra CI job — Mac Catalyst was considered and rejected. The UI
stays the finger-oriented virtual trackpad, so this build is a store-presence play, not a
good Mac client; the native app in `client/macos` is the better client and the only one
with the host role. Details and the trade-off in `16-release-macos.md` §2.
C++ logging (`client/ios/app/cpp/Log.h`) is `fprintf(stderr, ...)`, visible in the Xcode
console and Console.app.

## 3. Connect flow

1. **Address entry** — `ConnectView` (`client/ios/app/swift/ConnectView.swift`): a bare **IP
   address** field and a Connect button, nothing else. There is **no network discovery**; the
   port is the fixed constant `kDeskhubPort` = 47777 filled in by `ParseNetAddr` in the C++
   layer, which **rejects** any string containing `:`. No view-only checkbox — input is always
   shared (all of this was brought in line with the other clients on 2026-07-27).
2. **Source query** — `SessionModel.connect()` runs `DeskhubClient.listSources` (blocking
   `QuerySources`, LIST_SOURCES → SOURCE_LIST) in `Task.detached`. Every source is a shared
   display (window sources were removed 2026-07-27; rows use the "display" icon). More than
   one source shows
   `SourcePickerView` (radio-style rows, "Start viewing" button); exactly one — or a silent
   host, treated as "old host / single source", not an error — skips straight to source 0.
3. **Switching display mid-session** — the host shares *every* display, so `SessionModel`
   keeps the whole source list and `StreamView` has a `Display` button (shown only when there
   is more than one) that calls `switchSource`: `dh_stop` + `dh_start` with a different
   `sourceId`, without leaving the stream screen. There is no "change source" protocol
   message and none is needed — each (client, source) pair is already its own session.
   (The **Recents** list of up to 12 machines was deleted 2026-07-27; only the last address is
   remembered, pre-filled into the field.)
4. **No password step** — the auth layer (passwords, Keychain credentials, device tokens)
   was removed project-wide on 2026-07-27 (trusted-LAN decision, see 15-review-todo.md
   §A1); `dh_start` takes just the address and `sourceId`, `clientId` is random per
   session inside the native layer.

## 4. Streaming path

```
UdpSocket.RecvFrom → Reassembler (core, FEC + NACK) → decQueue_ (≤3)
  → VtDecoder (Annex-B → AVCC, CMSampleBuffer) → AVSampleBufferDisplayLayer
```

`VtDecoder` (`client/ios/app/cpp/decode/VtDecoder.h/.mm`) does **not** run an explicit
`VTDecompressionSession`: it enqueues H.264 `CMSampleBuffer`s directly into an
`AVSampleBufferDisplayLayer`, which hardware-decodes and composites itself (the iOS analogue
of Android's `releaseOutputBuffer(..., true)`). Details:

- **Annex-B → AVCC**: the stream is Annex-B with SPS/PPS repeated in-band on every IDR.
  `ParseAnnexB` splits NALs; SPS/PPS build a `CMVideoFormatDescription`
  (rebuilt only when the parameter bytes change); slice NALs get 4-byte length prefixes.
  Frames before the first IDR (no parameters yet) are silently skipped — not an error.
- **Low latency**: every sample gets `kCMSampleAttachmentKey_DisplayImmediately`; the stream
  has no B-frames. If the layer is not `readyForMoreMediaData` the frame is dropped rather
  than queued. A layer in `Failed` status (typical after returning from background) is
  flushed and `Decode` returns false, which makes `ClientLoop` rebuild the decoder and
  request an IDR.
- **Format changes**: host RECONFIG (`onReconfig`) stores the new size and sets
  `rebuildDecoder_`; the Decode thread shuts the decoder down and re-inits lazily once it has
  both a layer and negotiated dimensions. The host sends an IDR with RECONFIG.
- **IDR requests** funnel through one place in `NetThread` with a logged reason: packet loss,
  waiting-for-IDR, decoder failure, or frame-queue overflow.

The UI layer is `VideoLayerView` (`client/ios/app/swift/VideoLayerView.swift`), a
`UIViewRepresentable` whose `UIView.layerClass` is `AVSampleBufferDisplayLayer`
(`videoGravity = .resizeAspect`). `StreamView` sizes it with `.aspectRatio` from the
negotiated `videoWidth/Height`.

**Stats HUD**: `NetThread` builds a one-line summary every second
(`fps  Mbps  loss %  RTT ms  e2e ms`); `SessionModel.poll()` reads it via `dh_status_line` and
`StreamView` prints it as one line of text in the status bar. (It used to also be parsed for
RTT and drawn as a sparkline; that went with the design system on 2026-07-27.) The e2e figure
is measured at *enqueue* time, not at display time — a known caveat noted in `VtDecoder.h`
(`lastRenderedPtsUs`).

## 5. Input

All input funnels through `SessionModel`. There is no gate behind it any more — the `viewOnly`
flag was removed 2026-07-27; the funnel stays so views never touch the facade directly. The
C++ side queues events under `inputMutex_`; the Net thread
drains them into `ClientSession`, which sequences and redundantly retransmits them
(`InputSender`, see `07-input.md`). Any input sets `wantFocus_`, so the host receives
SET_FOCUS — since 2026-07-27 the host takes no action on `true` (it used to raise the shared
window); only the `false` edge matters, releasing held keys. Input only takes effect while
STREAMING.

- **Touch → mouse** — `TouchInputView` (`client/ios/app/swift/TouchInputView.swift`) is a
  *trackpad*, not direct touch: a visible cursor (SF Symbol `cursorarrow`) is moved by pan
  deltas. Gestures: drag = move cursor; single tap = left click (waits for the
  double-tap window to fail); double tap = right click; long-press-then-drag = hold left
  button and drag, released on lift. A move is re-sent immediately before every click so
  clicks land under the visible cursor. The overlay fills the middle row of the screen —
  letterbox included — but not the status/button bars above and below it, so a finger landing
  on a button no longer jogs the cursor. It is mounted whenever the session is streaming.

  Since the zoom work (2026-07-29) the cursor is stored in **content space** — a 0..1 point on
  the *host's* screen — and its on-screen position is derived through `VideoTransform`. What
  is sent is simply `cursor × 65535`, so changing zoom or pan never nudges the host pointer,
  and a finger delta divided by the *zoomed* frame width makes the cursor move slower the
  further you zoom in (the precision mode zooming exists to provide).
- **Pinch zoom / pan** (`VideoZoom.swift`, added 2026-07-29) — two fingers pinch to magnify the
  decoded frame (1× fit … 5×) and drag to move the viewport; a `Fit 2.3×` button in the bottom
  bar appears only while zoomed and returns to fit. Nothing is sent to the host: this is purely
  client-side magnification of pixels already received, since the agent always streams the
  source at its native resolution (`AgentLoop` builds the offer straight from the source size
  and ignores `HELLO.maxWidth/maxHeight`). `VideoTransform` is a direct port of the Android
  `VideoZoom.kt` — same `fitRect`/`videoRect` formulas, same auto-pan (`ensureVisible`) when
  the cursor nears an edge and same reverse rule (`clampToVisible`) that lets a hand-panned
  viewport push the cursor instead of yanking the view back. Two things differ from Android:
  - **No clipping host view is needed.** Android has to build a `FrameLayout` because a
    `SurfaceView` is a hole punched in the window that no one clips for it. Here the frame
    lives in an `AVSampleBufferDisplayLayer` — an ordinary `CALayer` — so zoom/pan is
    `.scaleEffect` + `.offset` on the video view and SwiftUI's `.clipped()` is enough. The
    sample buffers keep their native resolution; only the layer transform changes.
  - **Gesture arbitration is UIKit's.** A `UIPinchGestureRecognizer` plus a two-finger
    `UIPanGestureRecognizer` (both delegating to the view) are allowed to recognize
    simultaneously with each other *and* with an in-flight one-finger drag — the default
    would block a pinch started mid-drag. The one-finger paths then check `isTransforming`
    and stop moving the cursor while a viewport gesture runs, which is the iOS equivalent of
    Android's `multiTouch` latch. Taps and long-press need exactly one touch, so UIKit
    already rejects them during a pinch.
- **Virtual keyboard** — `KeyInputView` (`client/ios/app/swift/KeyInputView.swift`) is an
  invisible `UIKeyInput` view (ASCII keyboard, autocorrect off) toggled by the HUD keyboard
  button; a transparent accessory bar adds a "Done" dismiss button. Each typed scalar goes to
  `dh_char_tap`; `ClientLoop::QueueCharTap` uses core `CharToKeyChord` (`KeyMap.h`, US layout)
  to emit `[Shift↓] key↓ key↑ [Shift↑]`; backspace is sent as codepoint `0x08`. Non-ASCII
  characters are silently dropped.
- **Shortcut bar** — `StreamView`'s `kHotkeys` pill row supplies keys the iOS keyboard lacks:
  Esc, Tab, Enter, arrow keys, Del, Ctrl+C, Ctrl+V (Windows VK + scancode, bit 8 = E0 flag).
  Plain keys use `dh_key_tap`, combos use `dh_key_chord` (modifier held around the main key).
  Alt+Tab/Win are deliberately excluded (a rule from the per-window sharing era, kept because
  they are rarely useful from a hotkey bar).
- **Tap timing** — key-down is sent immediately; key-up is scheduled `kTapHoldUs` (50 ms)
  later in `delayedInput_`, so games polling the keyboard per frame still see the press.

## 6. Lifecycle

- `StreamView.onAppear` disables the idle timer and starts the 500 ms poll;
  `onDisappear` re-enables it, dismisses the keyboard, calls `dh_set_layer(NULL)`, and stops
  polling.
- `scenePhase` handling: on `.background` the layer is released (`dh_set_layer(NULL)` blocks
  until the Decode thread lets go); on `.active` it is re-attached. While layerless the Decode
  thread drops frames; re-attachment triggers a decoder rebuild plus an IDR request. The
  session itself keeps running in the C++ threads.
- `SessionModel.disconnect()` (End button, ended-overlay Back) calls
  `dh_stop`: `ClientLoop::Stop` raises `quit_`, joins both threads, and the Net thread sends
  a one-shot BYE so the host frees the session immediately. Host BYE / timeout / socket error
  set `endReason`, phase `.ended`, and `StreamView` shows the ended overlay.

## 7. Known limitations (as coded)

- **No scroll**: no gesture maps to mouse wheel; only move/left/right/drag exist (pinch zoom
  does exist, see Input — it is a client-side view transform, not an input event).
- **Zoom is client-side only**: it magnifies frames already decoded, so past ~1:1 with the
  device's pixels it stops recovering detail and starts interpolating (hence the 5× cap).
  Streaming only the visible region — host-side crop, real detail at any magnification, less
  bitrate — would need a new wire message plus a crop stage before NVENC and an encoder
  rebuild per zoom step; deliberately not done. Rationale in `VideoZoom.swift`.
- **Relative mouse is a stub**: `dh_mouse_move_rel` / `QueueMouseMoveRel` exist for an
  FPS-style pointer-lock mode, but no UI calls them (the "Lock" button was removed).
- **US-ASCII typing only**: `CharToKeyChord` covers the US layout; other characters are
  dropped in `QueueCharTap`.
- **e2e latency is approximate**: measured at sample enqueue, not on-glass
  (`VtDecoder::lastRenderedPtsUs` caveat); switching to `VTDecompressionSession` would be
  needed for exact timing.
- **Simulator-only make targets**: device/App Store builds require manual signing
  (`make/ios.mk`, `13-release-mobile.md`).
- **One global session** (`g_client` in `DeskhubClient.mm`); IPv4 only (`NetAddr`);
  no host discovery — addresses are typed or picked from recents.
