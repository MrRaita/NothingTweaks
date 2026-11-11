package com.souleven.nothingos.hooks

import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Member

/**
 * Essential key (Nothing Phone, scan code 250): per-gesture actions and per-function switches.
 * Runs in system_server.
 *
 * How the stock key works (verified against Nothing OS 4.1 / Android 16 bytecode and device logs):
 *   PhoneWindowManager.handleKeyGesture -> NtEssentialKeyImpl.onRecord(KeyEvent)   (nt-services.jar)
 *   onRecord forwards the raw DOWN/UP events to Essential Recorder (MainService2). The Recorder classifies the
 *   gesture itself (onClick ~300 ms after UP, onLongClick ~510 ms after DOWN, onDoubleClick on the second DOWN)
 *   and drives the capture flow: in IDLE a DOWN takes a screenshot immediately, a hold adds a voice recording,
 *   in FOCUS (capture bar open) a click saves and a hold records a voice note, a double press opens Essential Space.
 *
 * What this class does:
 *  1. Gesture actions: onRecord is hooked and the gesture (single / double / triple / long) is classified here.
 *     A gesture with a custom action is swallowed completely, so the system function bound to it never runs.
 *     Gestures left on "System default" are replayed to the original onRecord with their original timing.
 *  2. Function switches (capture, voice recording, opening Essential Space) are a policy on top of the same engine.
 *     They depend on the Recorder state, which EssentialRecorderHooks publishes (see KEY_ES_STATE).
 *     With the capture bar open, a hold can send the screenshot to a chosen app (see KEY_ES_SHARE_REQUEST).
 *  3. Essential Voice (dictation while a keyboard is open): NtEssentialKeyImpl classifies hold / double click itself
 *     and starts it through NtEssentialVoiceImpl.startVoiceInput(boolean, boolean, triggerType). Returning false
 *     there vetoes the start for that trigger (regardless of Transcribe / Translate).
 *  4. Keyboard open: NtEssentialKeyImpl sends every key event to the voice logic while a keyboard is shown
 *     (INtEssentialVoice.isImeVisible) and never forwards it to the Recorder, so no screenshot can be taken.
 *     The optional "screenshot while keyboard is open" replays a single press with isImeVisible() answering false.
 *  5. Presses that Nothing's own guards would drop ("disabled when screen off / locked", "activate with a single
 *     tap first") are passed through untouched, so custom behaviour obeys the same settings as the stock key.
 *  While nothing is customised the hook is a pure pass-through.
 *
 * Diagnostics: every step is logged as "[EssentialKey][<area>]" while the "Detailed key log" switch is on.
 */
class EssentialKeyHooks : HookModule {

    companion object {
        const val ACTION_DEFAULT = "default"
        const val ACTION_NONE = "none"
        const val ACTION_FLASHLIGHT = "flashlight"
        const val ACTION_RINGER = "ringer"
        const val ACTION_APP = "app"

        /** Internal only (never stored): replay the press as a quick click to Nothing's pipeline. */
        private const val ACTION_CLICK = "click"

        /** Internal only: ask the Recorder to send the current screenshot to the configured app. */
        private const val ACTION_SHARE_CAPTURE = "share_capture"

        /** Internal only: replay a single press so that Nothing's code forwards it to the Recorder although a keyboard is open. */
        private const val ACTION_CAPTURE_IME = "capture_ime"

        /** Bumped on every build so that a log line tells which code is really running (module updates need a reboot). */
        const val REV = 6

        val GESTURES = listOf("single", "double", "triple", "long")

        const val ACTION_LAUNCH_APP = "com.souleven.nothingos.action.ESSENTIAL_LAUNCH_APP"
        const val EXTRA_PACKAGE = "package"

        const val KEY_DEBUG_LOG = "pref_essential_debug_log"

        // Essential Voice (dictation) switches, per trigger
        const val KEY_BLOCK_VOICE_HOLD = "pref_essential_block_voice_hold"
        const val KEY_BLOCK_VOICE_DOUBLE = "pref_essential_block_voice_double"

        // Send the screenshot to an app on a hold while the capture bar is open
        const val KEY_CAPTURE_WITH_KEYBOARD = "pref_essential_capture_with_keyboard"
        const val KEY_SHARE_ENABLED = "pref_essential_share_enabled"
        const val KEY_SHARE_APP = "pref_essential_share_app"

        // Essential Space / Recorder functions that can be switched off (they follow the Recorder state)
        const val KEY_BLOCK_CAPTURE = "pref_essential_block_capture"
        const val KEY_BLOCK_VOICE_RECORD = "pref_essential_block_voice_record"
        const val KEY_BLOCK_OPEN_SPACE = "pref_essential_block_open_space"

        /**
         * Settings.Secure key that carries the state of Essential Recorder's capture flow
         * (written by EssentialRecorderHooks, read here). Values are the Recorder's own state names.
         */
        const val KEY_ES_STATE = "nt_tweaks_es_state"

        /**
         * Settings.Secure key system_server writes to ask the Recorder to share its current screenshot.
         * Value: "<package>|<uptimeMs>" (the uptime makes every request a change). Read by EssentialRecorderHooks.
         */
        const val KEY_ES_SHARE_REQUEST = "nt_tweaks_es_share"

        fun keyAction(gesture: String) = "pref_essential_${gesture}_action"
        fun keyApp(gesture: String) = "pref_essential_${gesture}_app"

        fun anyGestureUses(prefs: Prefs, action: String): Boolean =
            GESTURES.any { prefs.getString(keyAction(it), ACTION_DEFAULT) == action }

        fun isAppAssigned(prefs: Prefs, pkg: String): Boolean =
            pkg.isNotEmpty() && GESTURES.any {
                prefs.getString(keyAction(it), ACTION_DEFAULT) == ACTION_APP &&
                    prefs.getString(keyApp(it), "") == pkg
            }

        /** Off by default. A null [prefs] (process without prefs) never logs. */
        fun debugEnabled(prefs: Prefs?): Boolean = prefs?.getBoolean(KEY_DEBUG_LOG, false) ?: false

        /** One line per event: "[EssentialKey][area] u=<uptimeMs> message". Also used by the SystemUI/Recorder side. */
        fun log(prefs: Prefs?, area: String, msg: String) {
            if (!debugEnabled(prefs)) return
            XposedBridge.log("$TAG [EssentialKey][$area] u=${SystemClock.uptimeMillis()} $msg")
        }

        private const val IMPL_CLASS = "com.android.server.wm.NtEssentialKeyImpl"
        private const val VOICE_CLASS = "com.android.server.wm.NtEssentialVoiceImpl"
        private const val VIBRATOR_MANAGER_CLASS = "com.android.server.vibrator.VibratorManagerService"

        // Settings.Secure keys read by NtEssentialKeyImpl (verified in nt-services.jar)
        private const val SETTING_BLOCK_WHEN_OFF = "nt_block_essential_key"
        private const val SETTING_MISTOUCH = "nt_essential_key_mistouch_prevention"
        private const val USER_CURRENT = -2
        private const val MISTOUCH_WINDOW_MS = 2000L

        // NtEssentialVoiceImpl$TriggerType: 0 DEFAULT, 1 EK_LONG_CLICK, 2 EK_DOUBLE_CLICK
        private const val TRIGGER_LONG_CLICK = 1
        private const val TRIGGER_DOUBLE_CLICK = 2
        private const val VOICE_ACTIVE_MAX_MS = 120_000L

        private fun triggerName(t: Int) = when (t) {
            0 -> "DEFAULT"
            TRIGGER_LONG_CLICK -> "EK_LONG_CLICK"
            TRIGGER_DOUBLE_CLICK -> "EK_DOUBLE_CLICK"
            else -> "UNKNOWN($t)"
        }

        private const val LONG_PRESS_MS = 500L // Essential Recorder fires onLongClick ~505-520 ms after DOWN
        private const val MULTI_PRESS_WINDOW_MS = 300L
        private const val MAX_PRESSES = 3
        private const val CLICK_HOLD_MS = 80L
        private const val VIB_LOG_WINDOW_MS = 3000L
    }

    private class Press(val down: KeyEvent) {
        var up: KeyEvent? = null
    }

    // --- state touched only from the hook (input) thread ---
    @Volatile private var ownedPress = false
    private var pendingActivation = false
    private var stateInitDone = false

    /** Policy computed at the DOWN of the current press, handed to the handler thread. */
    @Volatile private var latestPolicy: Map<String, String> = emptyMap()
    @Volatile private var latestIme = false

    // --- state touched only from [handler] ---
    private val imeBypass = ThreadLocal<Boolean>() // true only while a replay must see "no keyboard"
    private val presses = ArrayList<Press>()
    private var longFired = false
    private var longReplayedDown = false
    private var sequenceStart = 0L
    private var lastUpUptime = 0L
    private var seqPolicy: Map<String, String> = emptyMap()
    private var seqIme = false

    // --- shared ---
    @Volatile private var origMethod: Member? = null
    @Volatile private var origTarget: Any? = null
    @Volatile private var cachedContext: Context? = null
    @Volatile private var lastActivityUptime = 0L
    @Volatile private var voiceActiveSince = 0L
    @Volatile private var activatedAtWall = 0L

    private lateinit var prefs: Prefs
    private lateinit var classLoader: ClassLoader
    private val thread = HandlerThread("NtTweaksEssentialKey").apply { start() }
    private val handler = Handler(thread.looper)

    private val longRunnable = Runnable { guarded("long press timeout") { onLongTimeout() } }
    private val windowRunnable = Runnable { guarded("multi press window") { classifyAndFinish() } }

    /** Lazy log: the message is only built when the debug log is on. */
    private inline fun d(area: String, msg: () -> String) {
        if (debugEnabled(prefs)) log(prefs, area, msg())
    }

    /**
     * Everything that runs on our own thread goes through here. An exception that escapes a HandlerThread
     * would kill system_server, so nothing is allowed to escape.
     */
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] $what failed: $t")
        }
    }

    private fun post(delayMs: Long = 0L, what: String = "handler task", block: () -> Unit) {
        handler.postDelayed({ guarded(what, block) }, delayMs)
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return
        this.prefs = prefs
        this.classLoader = lpparam.classLoader

        hookVoiceImpl(lpparam.classLoader)
        hookVibrationScan(lpparam.classLoader)

        val cls = findClass(IMPL_CLASS, lpparam.classLoader)
        if (cls == null) {
            XposedBridge.log("$TAG [EssentialKey][hook] $IMPL_CLASS not found, Essential key features disabled")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "onRecord", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onRecordHook(param)
                    } catch (t: Throwable) {
                        // never break the stock path because of us: fail open (event goes to Nothing's code)
                        ownedPress = false
                        pendingActivation = false
                        XposedBridge.log("$TAG [EssentialKey][err] hook error: $t")
                    }
                }
            })
            XposedBridge.log("$TAG [EssentialKey][hook] Hooked $IMPL_CLASS#onRecord (rev $REV)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][hook] Failed to hook onRecord: $t")
        }
    }

    private fun findClass(name: String, cl: ClassLoader): Class<*>? {
        XposedHelpers.findClassIfExists(name, cl)?.let { return it }
        for (alt in listOf(ClassLoader.getSystemClassLoader(), Thread.currentThread().contextClassLoader)) {
            if (alt == null) continue
            try {
                return Class.forName(name, false, alt)
            } catch (_: Throwable) {}
        }
        return null
    }

    // ---------------------------------------------------------------- Essential Voice (dictation) switches

    /**
     * NtEssentialKeyImpl starts Essential Voice through NtEssentialVoiceImpl.startVoiceInput(boolean, boolean, int)
     * where the int is the trigger type. Returning false there vetoes the start for that trigger only,
     * regardless of the Transcribe / Translate choice made in Nothing's settings.
     */
    private fun hookVoiceImpl(cl: ClassLoader) {
        val cls = findClass(VOICE_CLASS, cl)
        if (cls == null) {
            XposedBridge.log("$TAG [EssentialKey][hook] $VOICE_CLASS not found, Essential Voice switches disabled")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "startVoiceInput", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val type = param.args.getOrNull(2) as? Int ?: return
                        val block = (type == TRIGGER_LONG_CLICK && prefs.getBoolean(KEY_BLOCK_VOICE_HOLD, false)) ||
                            (type == TRIGGER_DOUBLE_CLICK && prefs.getBoolean(KEY_BLOCK_VOICE_DOUBLE, false))
                        d("voice") { "startVoiceInput args=${param.args.joinToString()} trigger=${triggerName(type)} veto=$block" }
                        if (block) {
                            param.setObjectExtra("ntBlocked", true)
                            param.result = false
                        }
                    } catch (_: Throwable) {}
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("ntBlocked") == true || param.hasThrowable()) return
                    val ok = param.result as? Boolean ?: return
                    d("voice") { "startVoiceInput accepted=$ok" }
                    if (ok) voiceActiveSince = SystemClock.uptimeMillis()
                }
            })
            XposedBridge.hookAllMethods(cls, "isImeVisible", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (imeBypass.get() == true) param.result = false
                }
            })
            XposedBridge.hookAllMethods(cls, "stopVoiceInput", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    d("voice") { "stopVoiceInput args=${param.args.joinToString()} result=${param.result}" }
                    voiceActiveSince = 0L
                }
            })
            XposedBridge.log("$TAG [EssentialKey][hook] Hooked $VOICE_CLASS#startVoiceInput/stopVoiceInput/isImeVisible (rev $REV)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][hook] voice hook failed: $t")
        }
    }

    /** True while a keyboard is shown, as NtEssentialVoiceImpl sees it (false when it cannot be asked). */
    private fun imeVisible(): Boolean = try {
        val factory = XposedHelpers.findClass("com.nothing.server.NtExtServiceFactory", classLoader)
        val type = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass("com.nothing.server.INtExtServiceFactory\$ExtType", classLoader), "NT_ESSENTIAL_VOICE"
        )
        XposedHelpers.callMethod(XposedHelpers.callStaticMethod(factory, "getOrCreate", type), "isImeVisible") as? Boolean ?: false
    } catch (t: Throwable) {
        d("err") { "isImeVisible lookup failed: $t" }
        false
    }

    /** True while an Essential Voice session started by the key runs: its stop-press must reach Nothing's code. */
    private fun voiceActive(): Boolean {
        val since = voiceActiveSince
        return since != 0L && SystemClock.uptimeMillis() - since < VOICE_ACTIVE_MAX_MS
    }

    // ---------------------------------------------------------------- vibration scan (diagnostics only)

    /**
     * DIAGNOSTIC: logs every vibration request made shortly after an Essential key event, to find the source of
     * double haptics. Does nothing (one comparison) while the debug log is off. Safe to remove.
     */
    private fun hookVibrationScan(cl: ClassLoader) {
        val cls = findClass(VIBRATOR_MANAGER_CLASS, cl) ?: run {
            XposedBridge.log("$TAG [EssentialKey][hook] $VIBRATOR_MANAGER_CLASS not found, vibration scan disabled")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "vibrate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val since = SystemClock.uptimeMillis() - lastActivityUptime
                        if (lastActivityUptime == 0L || since > VIB_LOG_WINDOW_MS || !debugEnabled(prefs)) return
                        val a = param.args
                        val usage = (a.getOrNull(4) as? VibrationAttributes)?.usage
                        d("vib") { "vibrate +${since}ms uid=${a.getOrNull(0)} pkg=${a.getOrNull(2)} usage=$usage reason=${a.getOrNull(5)} effect=${a.getOrNull(3)}" }
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(cls, "performHapticFeedback", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val since = SystemClock.uptimeMillis() - lastActivityUptime
                        if (lastActivityUptime == 0L || since > VIB_LOG_WINDOW_MS || !debugEnabled(prefs)) return
                        val a = param.args
                        d("vib") { "haptic +${since}ms uid=${a.getOrNull(0)} pkg=${a.getOrNull(2)} constant=${a.getOrNull(3)} reason=${a.getOrNull(4)} flags=${a.getOrNull(5)}" }
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.log("$TAG [EssentialKey][hook] Hooked $VIBRATOR_MANAGER_CLASS#vibrate/performHapticFeedback (scan)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][hook] vibration scan hook failed: $t")
        }
    }

    // ---------------------------------------------------------------- hook thread

    private fun onRecordHook(param: XC_MethodHook.MethodHookParam) {
        val event = param.args.getOrNull(0) as? KeyEvent ?: return
        val now = SystemClock.uptimeMillis()
        lastActivityUptime = now

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val reason = decidePress()
            d("evt") { "DOWN decision=${if (ownedPress) "OWN" else "PASS"} reason=$reason lat=${now - event.eventTime}ms $event" }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (!ownedPress && pendingActivation) {
                // mirrors NtEssentialKeyImpl: the UP of an un-activated press arms the key for 2 seconds
                pendingActivation = false
                activatedAtWall = System.currentTimeMillis()
                d("evt") { "UP arms key (mistouch prevention) for ${MISTOUCH_WINDOW_MS}ms" }
            }
            d("evt") { "UP decision=${if (ownedPress) "OWN" else "PASS"} lat=${now - event.eventTime}ms $event" }
        } else {
            d("evt") { "OTHER decision=${if (ownedPress) "OWN" else "PASS"} $event" }
        }

        if (!ownedPress) return // pass-through to Nothing's pipeline

        origMethod = param.method
        origTarget = param.thisObject
        param.result = null // swallow: Nothing's pipeline does not see this event

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return
        val copy = KeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val policy = latestPolicy
                val ime = latestIme
                post(what = "DOWN") { onDown(copy, policy, ime) }
            }
            KeyEvent.ACTION_UP -> post(what = "UP") { onUp(copy) }
        }
    }

    /** Decides once per press whether we take ownership. UP follows the decision of its DOWN. Returns the reason. */
    private fun decidePress(): String {
        pendingActivation = false
        val state = if (needsEssentialState()) esState() else "n/a"
        val ime = prefs.getBoolean(KEY_CAPTURE_WITH_KEYBOARD, false) && imeVisible()
        val policy = GESTURES.associateWith { effectiveAction(it, state, ime) }
        latestPolicy = policy
        latestIme = ime
        val changed = policy.filterValues { it != ACTION_DEFAULT }
        if (changed.isEmpty()) {
            ownedPress = false
            return "all-default (state=$state)"
        }
        if (voiceActive()) {
            ownedPress = false
            return "voice-active (a key press may be needed to stop Essential Voice)"
        }
        val guard = nativeGuardReason()
        if (guard != null) {
            ownedPress = false
            return guard
        }
        ownedPress = true
        return "state=$state keyboard=$ime policy=$changed"
    }

    private fun needsEssentialState() =
        prefs.getBoolean(KEY_BLOCK_CAPTURE, false) || prefs.getBoolean(KEY_BLOCK_VOICE_RECORD, false) ||
            shareConfigured()

    private fun shareConfigured() =
        prefs.getBoolean(KEY_SHARE_ENABLED, false) && prefs.getString(KEY_SHARE_APP, "").isNotEmpty()

    /**
     * What should happen for [gesture] given the Recorder [state]?
     * A custom action wins; otherwise the per-function switches decide; otherwise the system default.
     */
    private fun effectiveAction(gesture: String, state: String, keyboardOpen: Boolean): String {
        val custom = prefs.getString(keyAction(gesture), ACTION_DEFAULT)
        // "open app" without a chosen app must not swallow the stock behaviour for nothing
        if (custom == ACTION_APP && prefs.getString(keyApp(gesture), "").isEmpty()) return ACTION_DEFAULT
        if (custom != ACTION_DEFAULT) return custom
        val idle = state == "IDLE"
        val focus = state == "FOCUS" || state == "FOCUS_SHARE"
        val blockCapture = prefs.getBoolean(KEY_BLOCK_CAPTURE, false)
        val blockRecord = prefs.getBoolean(KEY_BLOCK_VOICE_RECORD, false)
        val blockSpace = prefs.getBoolean(KEY_BLOCK_OPEN_SPACE, false)
        return when (gesture) {
            "single" -> when {
                blockCapture && idle -> ACTION_NONE
                keyboardOpen -> ACTION_CAPTURE_IME // with a keyboard open Nothing would only use it for dictation
                else -> ACTION_DEFAULT
            }
            "double" -> if (blockSpace) ACTION_NONE else ACTION_DEFAULT
            "long" -> when {
                focus && shareConfigured() -> ACTION_SHARE_CAPTURE // hold with the capture bar open: send it on
                blockRecord -> if (focus) ACTION_CLICK else ACTION_NONE // in the capture bar: just save, no voice note
                blockCapture && idle -> ACTION_NONE // a hold in IDLE would take the screenshot too
                else -> ACTION_DEFAULT
            }
            else -> ACTION_DEFAULT
        }
    }

    /** Recorder capture-flow state as published by EssentialRecorderHooks (IDLE when unknown). */
    private fun esState(): String {
        val ctx = systemContext() ?: return "IDLE"
        return try {
            val resolver = ctx.contentResolver
            if (!stateInitDone) {
                // a state left over from a previous boot must not survive
                stateInitDone = true
                Settings.Secure.putString(resolver, KEY_ES_STATE, "IDLE")
            }
            Settings.Secure.getString(resolver, KEY_ES_STATE) ?: "IDLE"
        } catch (t: Throwable) {
            d("err") { "esState failed: $t" }
            "IDLE"
        }
    }

    /** Conditions under which NtEssentialKeyImpl would ignore the press; we leave those presses to it. */
    private fun nativeGuardReason(): String? {
        val ctx = systemContext() ?: return null
        return try {
            val resolver = ctx.contentResolver
            val blockOn = secureInt(resolver, SETTING_BLOCK_WHEN_OFF) != 0
            val interactive = (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
            val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: false
            val mistouchOn = secureInt(resolver, SETTING_MISTOUCH) != 0
            val armedFor = System.currentTimeMillis() - activatedAtWall
            d("cfg") { "blockWhenOff=$blockOn interactive=$interactive locked=$locked mistouch=$mistouchOn armedFor=${armedFor}ms" }
            when {
                blockOn && !interactive -> "native-guard:screen-off"
                blockOn && locked -> "native-guard:locked"
                mistouchOn && armedFor > MISTOUCH_WINDOW_MS -> {
                    pendingActivation = true
                    "native-guard:not-activated"
                }
                else -> null
            }
        } catch (t: Throwable) {
            d("err") { "nativeGuardReason failed: $t" }
            null
        }
    }

    private fun secureInt(resolver: ContentResolver, key: String): Int =
        try {
            XposedHelpers.callStaticMethod(Settings.Secure::class.java, "getIntForUser", resolver, key, 0, USER_CURRENT) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }

    // ---------------------------------------------------------------- handler thread

    private fun onDown(ev: KeyEvent, policy: Map<String, String>, ime: Boolean) {
        handler.removeCallbacks(windowRunnable)

        // stale state (e.g. a cancelled press whose UP never arrived)
        if (presses.isNotEmpty() && (presses.last().up == null || longFired)) {
            d("cls") { "stale state reset (presses=${presses.size} lastUpMissing=${presses.last().up == null} longFired=$longFired)" }
            resetState()
        }

        val now = SystemClock.uptimeMillis()
        if (presses.isEmpty()) {
            sequenceStart = now
            seqPolicy = policy
            seqIme = ime
            d("cfg") {
                "sequence policy=${policy.filterValues { it != ACTION_DEFAULT }} keyboard=$ime " +
                    "share=${prefs.getBoolean(KEY_SHARE_ENABLED, false)}/'${prefs.getString(KEY_SHARE_APP, "")}'"
            }
        }
        presses.add(Press(ev))
        d("cls") { "press#${presses.size} DOWN +${now - sequenceStart}ms gapSincePrevUp=${if (presses.size > 1) now - lastUpUptime else -1L}ms" }
        if (presses.size == 1) {
            longFired = false
            handler.removeCallbacks(longRunnable)
            handler.postDelayed(longRunnable, LONG_PRESS_MS)
        }
    }

    private fun onUp(ev: KeyEvent) {
        val last = presses.lastOrNull()
        if (last == null) {
            d("cls") { "UP without DOWN ignored" }
            return
        }
        last.up = ev
        val now = SystemClock.uptimeMillis()
        lastUpUptime = now
        d("cls") { "press#${presses.size} UP +${now - sequenceStart}ms held=${ev.eventTime - last.down.eventTime}ms" }

        if (longFired) {
            if (longReplayedDown) replay(ev, 0L, "long-up")
            resetState()
            return
        }

        handler.removeCallbacks(longRunnable)
        if (presses.size >= MAX_PRESSES) {
            classifyAndFinish()
        } else {
            handler.removeCallbacks(windowRunnable)
            handler.postDelayed(windowRunnable, MULTI_PRESS_WINDOW_MS)
        }
    }

    private fun onLongTimeout() {
        // only the first press of a sequence can become a long press
        if (presses.size != 1 || presses[0].up != null) return
        longFired = true
        val action = seqPolicy["long"] ?: ACTION_DEFAULT
        d("cls") { "LONG detected after ${LONG_PRESS_MS}ms -> $action" }
        when (action) {
            ACTION_DEFAULT -> {
                longReplayedDown = true
                replay(presses[0].down, 0L, "long-down")
            }
            ACTION_CLICK -> {
                // keep the hold away from the Recorder (no voice note) and give it a quick click instead
                longReplayedDown = false
                replayClick(presses[0].down)
            }
            else -> {
                longReplayedDown = false
                execute(action, "long")
            }
        }
    }

    private fun classifyAndFinish() {
        handler.removeCallbacks(longRunnable)
        val snapshot = ArrayList(presses)
        presses.clear()
        longFired = false
        longReplayedDown = false
        if (snapshot.isEmpty()) return

        val gesture = when (snapshot.size) {
            1 -> "single"
            2 -> "double"
            else -> "triple"
        }
        val action = seqPolicy[gesture] ?: ACTION_DEFAULT
        d("cls") { "classified $gesture (${snapshot.size} press) -> $action" }
        if (action == ACTION_DEFAULT || action == ACTION_CAPTURE_IME) {
            // give the stock pipeline exactly what it would have seen, with the original gaps
            val bypass = action == ACTION_CAPTURE_IME
            val base = snapshot[0].down.eventTime
            for ((i, p) in snapshot.withIndex()) {
                replay(p.down, (p.down.eventTime - base).coerceAtLeast(0L), "press${i + 1}-down", bypass)
                p.up?.let { replay(it, (it.eventTime - base).coerceAtLeast(0L), "press${i + 1}-up", bypass) }
            }
        } else {
            execute(action, gesture)
        }
    }

    private fun resetState() {
        handler.removeCallbacks(longRunnable)
        handler.removeCallbacks(windowRunnable)
        presses.clear()
        longFired = false
        longReplayedDown = false
    }

    private fun replay(ev: KeyEvent, delayMs: Long, label: String, bypassIme: Boolean = false) {
        val method = origMethod
        val target = origTarget
        if (method == null || target == null) {
            d("replay") { "$label skipped: original method unknown" }
            return
        }
        d("replay") { "$label scheduled in ${delayMs}ms" }
        post(delayMs, "replay $label") {
            d("replay") { "$label -> original onRecord${if (bypassIme) " (keyboard hidden from it)" else ""}" }
            if (bypassIme) imeBypass.set(true)
            try {
                XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any?>(KeyEvent(ev)))
            } finally {
                imeBypass.set(false)
            }
        }
    }

    /** DOWN now, UP shortly after: what a quick tap looks like to Nothing's pipeline. */
    private fun replayClick(down: KeyEvent) {
        replay(down, 0L, "click-down", seqIme)
        val up = KeyEvent.changeTimeRepeat(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), down.eventTime + CLICK_HOLD_MS, 0)
        replay(up, CLICK_HOLD_MS, "click-up", seqIme)
    }

    // ---------------------------------------------------------------- actions

    private fun systemContext(): Context? {
        cachedContext?.let { return it }
        return try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            (XposedHelpers.callMethod(at, "getSystemContext") as? Context)?.also { cachedContext = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun execute(action: String, gesture: String) {
        lastActivityUptime = SystemClock.uptimeMillis()
        val ctx = systemContext()
        if (ctx == null) {
            d("act") { "$gesture -> $action aborted: no system context" }
            return
        }
        d("act") { "$gesture -> $action" }
        when (action) {
            ACTION_NONE -> Unit
            // SystemUI's torch toggle already gives its own haptic, so none is added here
            ACTION_FLASHLIGHT ->
                sendToSystemUi(ctx, Intent(TorchHooks.ACTION_TOGGLE_TORCH).putExtra(TorchHooks.EXTRA_ESSENTIAL, true))
            ACTION_RINGER -> cycleRinger(ctx)
            ACTION_SHARE_CAPTURE -> requestShare(ctx)
            ACTION_APP -> {
                val pkg = prefs.getString(keyApp(gesture), "")
                if (pkg.isEmpty()) {
                    d("act") { "$gesture -> app aborted: no package selected" }
                    return
                }
                sendToSystemUi(ctx, Intent(ACTION_LAUNCH_APP).putExtra(EXTRA_PACKAGE, pkg))
            }
            else -> d("act") { "unknown action '$action'" }
        }
    }

    /** Asks EssentialRecorderHooks (in the Recorder process) to send the current screenshot to the chosen app. */
    private fun requestShare(ctx: Context) {
        val pkg = prefs.getString(KEY_SHARE_APP, "")
        if (pkg.isEmpty()) {
            d("act") { "share aborted: no app selected" }
            return
        }
        try {
            vibrate(ctx, "share capture")
            Settings.Secure.putString(ctx.contentResolver, KEY_ES_SHARE_REQUEST, "$pkg|${SystemClock.uptimeMillis()}")
            d("act") { "share requested -> $pkg" }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] share request failed: $t")
        }
    }

    private fun sendToSystemUi(ctx: Context, intent: Intent) {
        try {
            intent.setPackage("com.android.systemui")
            val all = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL")
            XposedHelpers.callMethod(ctx, "sendBroadcastAsUser", intent, all)
            d("act") { "broadcast sent action=${intent.action} extras=${intent.extras?.keySet()}" }
        } catch (t: Throwable) {
            try {
                ctx.sendBroadcast(intent)
                d("act") { "broadcast sent via fallback (${t.message})" }
            } catch (_: Throwable) {
                XposedBridge.log("$TAG [EssentialKey][err] broadcast failed: ${t.message}")
            }
        }
    }

    /** Normal -> Vibrate -> Silent -> Normal */
    private fun cycleRinger(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val before = am.ringerMode
            val next = when (before) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            // SystemUI gives its own haptic when the ringer changes to vibrate (seen in the vibration log);
            // for the other changes nothing vibrates, so we do it. Result: always exactly one haptic.
            if (next != AudioManager.RINGER_MODE_VIBRATE) vibrate(ctx, "ringer -> $next")
            var how = "setRingerModeInternal"
            try {
                XposedHelpers.callMethod(am, "setRingerModeInternal", next)
            } catch (_: Throwable) {
                how = "setRingerMode"
                am.ringerMode = next
            }
            d("act") { "ringer $before -> $next via $how (now ${am.ringerMode})" }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] ringer change failed: $t")
        }
    }

    private fun vibrate(ctx: Context, why: String) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (v.hasVibrator()) {
                d("vib") { "own EFFECT_CLICK requested ($why)" }
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (_: Throwable) {}
    }
}
