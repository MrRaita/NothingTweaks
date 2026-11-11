# Essential key (Nothing Phone) – maintainer notes

Features (Settings → "Essential key", "Essential key · Essential Space", "Essential key · Essential Voice"):

* Per-gesture action for **single / double / triple / long press**: system default, do nothing, toggle flashlight,
  cycle sound mode, open an app. A gesture with a custom action replaces the system function of that gesture.
* Switches for the stock functions: disable screenshot capture, disable voice recording on hold (with the capture
  bar open a hold just saves), disable opening Essential Space on double press.
* **Send screenshot to an app**: with the capture bar open, a hold sends the screenshot to the chosen app
  (e.g. a translator) instead of recording a voice note.
* **Screenshot with keyboard open**: a single press takes the screenshot although a keyboard is shown.
* **Essential Voice** (dictation into a text field while a keyboard is open): switches to disable it on a long
  press and on a double press, whatever Transcribe / Translate is chosen in Nothing's settings.

## How it works

Verified on Nothing OS 4.1 / Android 16 (Phone 4a) from bytecode and device logs:

```
PhoneWindowManager.handleKeyGesture (scan code 250)
  -> NtEssentialKeyImpl.onRecord(KeyEvent)            nt-services.jar, system_server
     -> raw DOWN/UP forwarded to Essential Recorder (MainService2)
        Recorder classifies: onClick (~300 ms after UP), onLongClick (~510 ms after DOWN), onDoubleClick (2nd DOWN)
        IDLE: a DOWN takes a screenshot at once; FOCUS (capture bar): click saves, hold records a voice note
```

| class | process | job |
|-------|---------|-----|
| `EssentialKeyHooks` | system_server | hooks `onRecord`, classifies the gesture, swallows or replays the events, runs the actions |
| `EssentialRecorderHooks` | com.nothing.ntessentialrecorder | publishes the Recorder state (`Settings.Secure nt_tweaks_es_state`) read from its own `setEssentialState` log line; keeps a copy of the screenshot and shares it on request; MediaStore diagnostics |
| `TorchHooks`, `PowerButtonHooks` | SystemUI | execute "flashlight" / "open app" (broadcast sent by system_server) |

* Nothing is customised → pure pass-through (no delay).
* Otherwise the engine owns the presses: gestures left on default are **replayed** to the original `onRecord` with
  their original timing, so the stock behaviour is kept (single press ≈ +300 ms, native hold ≈ +500 ms later).
* Essential Voice: `NtEssentialKeyImpl` classifies hold / double click itself and calls
  `NtEssentialVoiceImpl.startVoiceInput(boolean, boolean, triggerType)` (1 = long click, 2 = double click).
  Returning `false` vetoes the start. While a session started by the key runs, presses are passed through so the
  press that stops it still works.
* Screenshot sharing: system_server (hold in `FOCUS`) writes `"<package>|<uptime>"` to
  `Settings.Secure nt_tweaks_es_share`; the Recorder process observes it, writes the screenshot it kept from
  `ScreenCapture$ScreenshotHardwareBuffer.asBitmap()` to MediaStore (`Pictures/EssentialKey/essential_capture.png`,
  replaced on every share) and starts `ACTION_SEND image/png` for that package with a read grant. The Recorder only
  sends to the package that is configured in the module, whatever the request says.
* Keyboard open: while `INtEssentialVoice.isImeVisible()` is true, `onRecord` hands every event to the voice
  logic and returns before anything is forwarded to the Recorder (verified in bytecode and logs: no wake lock, no
  Recorder transition). With "screenshot with keyboard open" a single press is replayed with `isImeVisible()`
  answering false (thread-local, only for that replay), so it reaches the Recorder; double press and hold stay
  with the voice logic, and while an Essential Voice session runs every press is passed through untouched.
* Presses that Nothing's own guards would drop ("disabled when screen off / locked", "activate with a single tap
  first") are passed through untouched, so custom actions obey the same settings.

## Requirements

`com.nothing.ntessentialrecorder` must be in the module's LSPosed scope for the Essential Space switches and
the screenshot sharing (the capture-bar state and the screenshot come from there). Gesture actions and the
Essential Voice switches only need `android` and `com.android.systemui`.

## Known limits

* "Disable screenshot capture" also swallows a hold in IDLE (a hold takes the screenshot too); a double press in
  IDLE can still flash a capture that is cancelled right away.
* Screenshot sharing: a custom action on the long press wins over it; the capture bar stays open after sharing (a
  click still saves or dismisses it as usual); the target app must accept `ACTION_SEND` with an image; one PNG is
  kept in `Pictures/EssentialKey/`.
* The Essential Voice session flag times out after 2 minutes in case Nothing's code never reports a stop.
* An unknown Recorder state is treated as IDLE.
* The SystemUI torch receiver (`TorchHooks`) is registered exported (pre-existing); only system_server needs to
  reach it, so `RECEIVER_NOT_EXPORTED` would be safe there as well.

## Debug log

Settings → "Essential key log" (off by default), then `su -c "logcat -v time" | grep EssentialKey`.
Lines are `[EssentialKey][area] u=<uptimeMs> message`:

| area   | shows |
|--------|-------|
| hook   | hooks installed at boot (and failures), with the build revision (`rev`): a module update only takes effect after a reboot, so check it matches |
| evt    | every raw key event at `onRecord`: DOWN/UP, OWN (swallowed) or PASS, and why |
| cfg    | settings seen at a press: block when off/locked, interactive, locked, activate-by-tap, sequence policy |
| cls    | gesture classification: press count, gaps, hold time, long press detection, result |
| replay | events given back to Nothing's pipeline |
| act    | the action that ran (broadcast, ringer before/after, app package) |
| vib    | own vibration and every vibration/haptic request seen in system_server within 3 s of key activity (diagnostic, removable) |
| su     | SystemUI side: torch / launch broadcasts, haptics, debounce drops |
| voice  | `startVoiceInput` / `stopVoiceInput` calls, trigger type, veto |
| share  | Recorder side: screenshot copy kept, share requests, result |
| es, ms | Recorder side: published state; MediaStore and `Bitmap.compress` calls (diagnostic, removable) |
| err    | caught errors |

Recorder's own log for the same moments: `logcat | grep -E " (u|c3|m3) +:"`.
