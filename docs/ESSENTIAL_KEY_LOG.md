# Essential key – debug log guide

Turn the "Detailed key log" switch on (default in this build), then:

```
su -c "logcat -v time" | grep "EssentialKey"
```

Every line is `[EssentialKey][area] u=<uptimeMs> message`.

| area   | what it shows |
|--------|---------------|
| hook   | which hooks were installed at boot (and which failed) |
| evt    | every raw key event reaching `NtEssentialKeyImpl.onRecord`: DOWN/UP, whether we OWN (swallow) or PASS it, and why |
| cfg    | settings seen at the moment of a press: block when screen off/locked, interactive, locked, activate-by-tap |
| cls    | gesture classification: press count, gaps, hold time, long press detection, result |
| replay | presses given back to Nothing's pipeline (scheduled and executed) |
| act    | the action that ran: broadcast sent, ringer mode before/after, app package |
| voice  | `NtEssentialVoiceImpl.startVoiceInput/stopVoiceInput` calls, trigger type, veto |
| vib    | own vibration request and every vibration/haptic request seen in system_server within 3 s of key activity (uid, package, usage, reason, effect) |
| su     | SystemUI side: torch broadcast, launch broadcast, haptics of TorchHooks / PowerButtonHooks, debounce drops |
| err    | caught errors |

Useful extra lines: `logcat | grep -E "NtEssentialKeyImpl|NtEssentialVoiceImpl"` (Nothing's own logs) and
`su -c dumpsys vibrator_manager` (recent vibrations with the package that asked for them).
