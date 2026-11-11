# Essential key (Nothing Phone) – maintainer notes

Settings → "Essential key", "Essential key · Essential Space", "Essential key · Essential Voice".

* **Gesture actions** for single / double / triple / long press: system default, do nothing, toggle flashlight,
  cycle sound mode, open an app. A gesture with a custom action replaces the system function of that gesture.
* **Essential Space switches**: disable screenshot capture, disable voice recording on hold (with the capture bar
  open a hold just saves), disable opening Essential Space on double press, and take the screenshot with a
  single press although a keyboard is open.
* **Essential Voice switches** (dictation into a text field): disable it on a long press / on a double press,
  whatever Transcribe / Translate is chosen in Nothing's settings.

## How the stock key works

Verified on Nothing OS 4.1 / Android 16 (Phone 4a) from bytecode and device logs:

```
PhoneWindowManager.handleKeyGesture (scan code 250)
  -> NtEssentialKeyImpl.onRecord(KeyEvent)            nt-services.jar, system_server
       keyboard shown (INtEssentialVoice.isImeVisible): events go to the voice logic only, nothing is forwarded
       otherwise: raw DOWN/UP forwarded to Essential Recorder (MainService2)
          Recorder classifies: onClick (~300 ms after UP), onLongClick (~510 ms after DOWN), onDoubleClick (2nd DOWN)
          IDLE: a DOWN takes a screenshot at once; a hold adds a voice recording
          FOCUS (capture bar open): click saves, hold records a voice note
          double press opens Essential Space
       hold / double click with a keyboard shown: NtEssentialVoiceImpl.startVoiceInput(boolean, boolean, triggerType)
```

## What this module does

| class | process | job |
|-------|---------|-----|
| `EssentialKeyHooks` | system_server | hooks `onRecord`, classifies the gesture, swallows or replays the events, runs the actions, vetoes Essential Voice |
| `EssentialRecorderHooks` | com.nothing.ntessentialrecorder | publishes the capture-flow state (`Settings.Secure nt_tweaks_es_state`), read from the Recorder's own `setEssentialState` log line |
| `TorchHooks`, `PowerButtonHooks` | SystemUI | execute "flashlight" / "open app" (broadcast sent by system_server) |

* Nothing is customised → pure pass-through, no delay.
* Otherwise the engine owns the presses: gestures left on default are **replayed** to the original `onRecord` with
  their original timing, so the stock behaviour is kept (single press ≈ +300 ms, native hold ≈ +500 ms later).
* Presses that Nothing's own guards would drop ("disabled when screen off / locked", "activate with a single tap
  first") are passed through untouched, so custom actions obey the same settings.
* Essential Voice: returning `false` from `startVoiceInput` vetoes the start for that trigger (1 = long click,
  2 = double click). While a session started by the key runs, presses are passed through so the press that stops
  it still works (the flag times out after 2 minutes in case no stop is ever reported).
* Screenshot with keyboard open: a single press is replayed with `isImeVisible()` answering false (thread-local,
  only for that replay), so it reaches the Recorder. Double press and hold stay with the voice logic.
* The Recorder state is observed from system_server, no settings read happens on the input thread.

## Requirements

`com.nothing.ntessentialrecorder` must be in the module's LSPosed scope for the Essential Space switches (they
depend on the capture-bar state). Gesture actions, the keyboard switch and the Essential Voice switches only need
`android` and `com.android.systemui`. A module update only takes effect after a reboot.

## Known limits

* "Disable screenshot capture" also swallows a hold in IDLE (a hold takes the screenshot too); a double press in
  IDLE can still flash a capture that is cancelled right away.
* An unknown Recorder state is treated as IDLE.
* The SystemUI torch receiver (`TorchHooks`) is registered exported (pre-existing); only system_server needs to
  reach it, so `RECEIVER_NOT_EXPORTED` would be safe there as well. The "open app" receiver added for this
  feature is not exported and only launches an app that is assigned to a gesture.

## Errors

Hook failures and caught exceptions are written once to the Xposed log with the prefix `[EssentialKey]`.
