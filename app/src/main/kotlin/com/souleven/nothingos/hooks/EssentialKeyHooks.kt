package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Member

/**
 * Remaps the Nothing "Essential key" (scan code 250) per gesture: single / double / triple / long press.
 *
 * How it works (verified against Nothing OS 4.1 / Android 16 bytecode):
 *   PhoneWindowManager.handleKeyGesture -> NtEssentialKeyImpl.onRecord(KeyEvent) (nt-services.jar, system_server)
 *   -> raw DOWN/UP events are forwarded to Essential Recorder (MainService2), which classifies the gesture.
 *
 * We hook NtEssentialKeyImpl.onRecord and classify the gesture ourselves. A gesture that has a custom action
 * is swallowed completely, so the system function bound to it never runs (= "auto disable").
 * Gestures left on "System default" are replayed to the original onRecord with their original timing,
 * so the stock behaviour stays intact. While every gesture is on "System default" the hook is a pure pass-through.
 */
class EssentialKeyHooks : HookModule {

    companion object {
        const val ACTION_DEFAULT = "default"
        const val ACTION_NONE = "none"
        const val ACTION_FLASHLIGHT = "flashlight"
        const val ACTION_RINGER = "ringer"
        const val ACTION_APP = "app"

        val GESTURES = listOf("single", "double", "triple", "long")

        const val ACTION_LAUNCH_APP = "com.souleven.nothingos.action.ESSENTIAL_LAUNCH_APP"
        const val EXTRA_PACKAGE = "package"

        fun keyAction(gesture: String) = "pref_essential_${gesture}_action"
        fun keyApp(gesture: String) = "pref_essential_${gesture}_app"

        /** True if at least one gesture uses [action]. Used by the SystemUI side to validate incoming broadcasts. */
        fun anyGestureUses(prefs: Prefs, action: String): Boolean =
            GESTURES.any { prefs.getString(keyAction(it), ACTION_DEFAULT) == action }

        /** True if [pkg] is currently assigned to a gesture with the "open app" action. */
        fun isAppAssigned(prefs: Prefs, pkg: String): Boolean =
            pkg.isNotEmpty() && GESTURES.any {
                prefs.getString(keyAction(it), ACTION_DEFAULT) == ACTION_APP &&
                    prefs.getString(keyApp(it), "") == pkg
            }

        // Independent switches that turn off Nothing's own Essential key AI features
        // (they work without assigning anything to a gesture).
        const val KEY_BLOCK_VOICE_HOLD = "pref_essential_block_voice_hold"
        const val KEY_BLOCK_VOICE_DOUBLE = "pref_essential_block_voice_double"
        const val KEY_BLOCK_FLIP_TO_RECORD = "pref_essential_block_flip_to_record"

        // Settings.Secure keys read by NtEssentialKeyImpl (verified in nt-services.jar).
        // Values of NtEssentialVoiceImpl$TriggerAction: 0 = TRANSCRIBE, 1 = TRANSLATE, 2 = NO_ACTION
        private const val SETTING_VOICE_HOLD = "nt_essential_voice_action_hold"
        private const val SETTING_VOICE_DOUBLE = "nt_essential_voice_action_double_click"
        private const val SETTING_FLIP_TO_RECORD = "nt_flip_to_record_state"
        private const val VOICE_NO_ACTION = 2

        private const val IMPL_CLASS = "com.android.server.wm.NtEssentialKeyImpl"
        private const val LONG_PRESS_MS = 400L
        private const val MULTI_PRESS_WINDOW_MS = 300L
        private const val MAX_PRESSES = 3
    }

    private class Press(val down: KeyEvent) {
        var up: KeyEvent? = null
    }

    // --- state touched only from the hook thread ---
    @Volatile private var ownedPress = false

    // --- state touched only from [handler] ---
    private val presses = ArrayList<Press>()
    private var longFired = false
    private var longReplayedDown = false

    @Volatile private var origMethod: Member? = null
    @Volatile private var origTarget: Any? = null

    private lateinit var prefs: Prefs
    private lateinit var classLoader: ClassLoader
    private val thread = HandlerThread("NtTweaksEssentialKey").apply { start() }
    private val handler = Handler(thread.looper)

    private val longRunnable = Runnable { onLongTimeout() }
    private val windowRunnable = Runnable { classifyAndFinish() }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return
        this.prefs = prefs
        this.classLoader = lpparam.classLoader

        hookAiFunctionSwitches(lpparam.classLoader)

        val cls = findImplClass(lpparam.classLoader)
        if (cls == null) {
            XposedBridge.log("$TAG [EssentialKey] $IMPL_CLASS not found, feature disabled")
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "onRecord", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onRecordHook(param)
                    } catch (t: Throwable) {
                        // never break the stock path because of us
                        XposedBridge.log("$TAG [EssentialKey] hook error: ${t.message}")
                    }
                }
            })
            XposedBridge.log("$TAG [EssentialKey] Hooked $IMPL_CLASS#onRecord")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Failed to hook onRecord: ${t.message}")
        }
    }

    private fun findImplClass(cl: ClassLoader): Class<*>? {
        XposedHelpers.findClassIfExists(IMPL_CLASS, cl)?.let { return it }
        for (alt in listOf(ClassLoader.getSystemClassLoader(), Thread.currentThread().contextClassLoader)) {
            if (alt == null) continue
            try {
                return Class.forName(IMPL_CLASS, false, alt)
            } catch (_: Throwable) {}
        }
        return null
    }

    // ---------------------------------------------------------------- AI function switches

    private var lastAiState = Triple(false, false, false)

    /**
     * Makes NtEssentialKeyImpl read "no action" / "off" for the selected Essential Voice features.
     * Nothing's own settings stay untouched (nothing is written), the impl simply sees a different value.
     * It observes these settings, so a notifyChange makes the change effective immediately.
     */
    private fun hookAiFunctionSwitches(cl: ClassLoader) {
        val secure = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", cl) ?: return
        try {
            XposedBridge.hookAllMethods(secure, "getIntForUser", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(1) as? String ?: return
                    when (name) {
                        SETTING_VOICE_HOLD ->
                            if (prefs.getBoolean(KEY_BLOCK_VOICE_HOLD, false)) param.result = VOICE_NO_ACTION
                        SETTING_VOICE_DOUBLE ->
                            if (prefs.getBoolean(KEY_BLOCK_VOICE_DOUBLE, false)) param.result = VOICE_NO_ACTION
                        SETTING_FLIP_TO_RECORD ->
                            if (prefs.getBoolean(KEY_BLOCK_FLIP_TO_RECORD, false)) param.result = 0
                    }
                }
            })
            XposedBridge.log("$TAG [EssentialKey] Hooked Settings.Secure#getIntForUser for AI switches")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] AI switch hook failed: ${t.message}")
            return
        }

        // re-read the settings inside NtEssentialKeyImpl whenever one of our switches changes
        prefs.addListener {
            val now = Triple(
                prefs.getBoolean(KEY_BLOCK_VOICE_HOLD, false),
                prefs.getBoolean(KEY_BLOCK_VOICE_DOUBLE, false),
                prefs.getBoolean(KEY_BLOCK_FLIP_TO_RECORD, false)
            )
            handler.post {
                val prev = lastAiState
                if (now == prev) return@post
                lastAiState = now
                val ctx = systemContext() ?: return@post
                try {
                    val resolver = ctx.contentResolver
                    fun poke(changed: Boolean, key: String) {
                        if (changed) resolver.notifyChange(android.provider.Settings.Secure.getUriFor(key), null)
                    }
                    poke(now.first != prev.first, SETTING_VOICE_HOLD)
                    poke(now.second != prev.second, SETTING_VOICE_DOUBLE)
                    poke(now.third != prev.third, SETTING_FLIP_TO_RECORD)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG [EssentialKey] notifyChange failed: ${t.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- hook thread

    private fun onRecordHook(param: XC_MethodHook.MethodHookParam) {
        val event = param.args.getOrNull(0) as? KeyEvent ?: return

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // decide once per press; UP follows the decision of its DOWN
            ownedPress = GESTURES.any { prefs.getString(keyAction(it), ACTION_DEFAULT) != ACTION_DEFAULT }
        }
        if (!ownedPress) return // pure pass-through

        origMethod = param.method
        origTarget = param.thisObject
        param.result = null // swallow: Nothing's pipeline does not see this event

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return
        val copy = KeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> handler.post { onDown(copy) }
            KeyEvent.ACTION_UP -> handler.post { onUp(copy) }
        }
    }

    // ---------------------------------------------------------------- handler thread

    private fun onDown(ev: KeyEvent) {
        handler.removeCallbacks(windowRunnable)

        // stale state (e.g. a cancelled press whose UP never arrived)
        if (presses.isNotEmpty() && (presses.last().up == null || longFired)) resetState()

        presses.add(Press(ev))
        if (presses.size == 1) {
            longFired = false
            handler.removeCallbacks(longRunnable)
            handler.postDelayed(longRunnable, LONG_PRESS_MS)
        }
    }

    private fun onUp(ev: KeyEvent) {
        val last = presses.lastOrNull() ?: return
        last.up = ev

        if (longFired) {
            if (longReplayedDown) replay(ev, 0L) // long press left on system default: finish it
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
        val action = prefs.getString(keyAction("long"), ACTION_DEFAULT)
        if (action == ACTION_DEFAULT) {
            longReplayedDown = true
            replay(presses[0].down, 0L)
        } else {
            longReplayedDown = false
            execute(action, "long")
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
        val action = prefs.getString(keyAction(gesture), ACTION_DEFAULT)
        XposedBridge.log("$TAG [EssentialKey] classified $gesture (${snapshot.size} press) -> $action")
        if (action == ACTION_DEFAULT) {
            // give the stock pipeline exactly what it would have seen, with the original gaps
            val base = snapshot[0].down.eventTime
            for (p in snapshot) {
                replay(p.down, (p.down.eventTime - base).coerceAtLeast(0L))
                p.up?.let { replay(it, (it.eventTime - base).coerceAtLeast(0L)) }
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

    private fun replay(ev: KeyEvent, delayMs: Long) {
        val method = origMethod
        val target = origTarget
        if (method == null || target == null) return
        handler.postDelayed({
            try {
                XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any?>(KeyEvent(ev)))
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [EssentialKey] replay failed: ${t.message}")
            }
        }, delayMs)
    }

    // ---------------------------------------------------------------- actions

    private fun systemContext(): Context? = try {
        val atClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
        XposedHelpers.callMethod(at, "getSystemContext") as? Context
    } catch (_: Throwable) {
        null
    }

    private fun execute(action: String, gesture: String) {
        val ctx = systemContext() ?: return
        XposedBridge.log("$TAG [EssentialKey] $gesture -> $action")
        when (action) {
            ACTION_NONE -> return
            ACTION_FLASHLIGHT -> {
                vibrate(ctx)
                sendToSystemUi(ctx, Intent(TorchHooks.ACTION_TOGGLE_TORCH).putExtra(TorchHooks.EXTRA_ESSENTIAL, true))
            }
            ACTION_RINGER -> {
                vibrate(ctx)
                cycleRinger(ctx)
            }
            ACTION_APP -> {
                val pkg = prefs.getString(keyApp(gesture), "")
                if (pkg.isEmpty()) return
                sendToSystemUi(ctx, Intent(ACTION_LAUNCH_APP).putExtra(EXTRA_PACKAGE, pkg))
            }
        }
    }

    private fun sendToSystemUi(ctx: Context, intent: Intent) {
        try {
            intent.setPackage("com.android.systemui")
            val all = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL")
            XposedHelpers.callMethod(ctx, "sendBroadcastAsUser", intent, all)
        } catch (t: Throwable) {
            try {
                ctx.sendBroadcast(intent)
            } catch (_: Throwable) {
                XposedBridge.log("$TAG [EssentialKey] broadcast failed: ${t.message}")
            }
        }
    }

    /** Normal -> Vibrate -> Silent -> Normal */
    private fun cycleRinger(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val next = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            try {
                XposedHelpers.callMethod(am, "setRingerModeInternal", next)
            } catch (_: Throwable) {
                am.ringerMode = next
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] ringer change failed: ${t.message}")
        }
    }

    private fun vibrate(ctx: Context) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (_: Throwable) {}
    }
}
