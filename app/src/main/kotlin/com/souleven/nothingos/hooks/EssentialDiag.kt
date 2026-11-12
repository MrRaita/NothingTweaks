package com.souleven.nothingos.hooks

import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * TEMPORARY diagnostic, runs inside Essential Recorder. Delete this file, its call in EssentialRecorderHooks and
 * the "pref_essential_diag_log" switch before the pull request.
 *
 * Question: why is an Essential screenshot taken on the Nothing launcher (home / app drawer) not saved?
 * Bytecode: the Recorder's capture code only marks a capture as valid when the keyguard is not locked and the
 * screenshot's package is not "com.nothing.launcher". This log shows, per capture, the foreground app, the keyguard
 * state and every Recorder log line about saving, capturing, skipping or failing, so a capture on the launcher can
 * be compared with one in another app. Output lines: "[EssentialDiag] u=<uptimeMs> ...".
 */
object EssentialDiag {
    const val KEY_DIAG_LOG = "pref_essential_diag_log"

    private val STATE = Regex("""^setEssentialState, from (\w+), to (\w+)""")
    private val INTERESTING = Regex("(?i)sav|captur|screenshot|skip|ignor|fail|error|launcher|keyguard|screen off|timeout")

    private val busy = ThreadLocal<Boolean>() // XposedBridge.log may itself go through Log.i
    private lateinit var prefs: Prefs

    fun install(prefs: Prefs) {
        this.prefs = prefs
        for (level in listOf("d", "i", "w", "e")) {
            XposedBridge.hookAllMethods(Log::class.java, level, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onLog(level, param.args)
                    } catch (_: Throwable) {}
                }
            })
        }
    }

    private fun onLog(level: String, args: Array<Any?>) {
        if (busy.get() == true || !prefs.getBoolean(KEY_DIAG_LOG, false)) return
        val tag = args.getOrNull(0) as? String ?: return
        val msg = args.getOrNull(1) as? String ?: return
        if (tag == "VectorLegacyBridge" || msg.contains("EssentialDiag")) return

        val state = STATE.find(msg)
        when {
            state != null -> {
                emit("state ${state.groupValues[1]} -> ${state.groupValues[2]}")
                if (state.groupValues[2] == "PREPARING") emit("capture starts: ${snapshot()}")
            }
            INTERESTING.containsMatchIn(msg) -> emit("$level/$tag: ${msg.take(300)}")
        }
    }

    // ---- system_server side (called from EssentialKeyHooks, remove together with this file) ----

    fun press(prefs: Prefs, state: String, switchOn: Boolean, keyboardShown: Boolean, policy: Map<String, String>, owned: Boolean) {
        if (!prefs.getBoolean(KEY_DIAG_LOG, false)) return
        val changed = policy.filterValues { it != EssentialKeyHooks.ACTION_DEFAULT }
        XposedBridge.log(
            "$TAG [EssentialDiag] u=${SystemClock.uptimeMillis()} key DOWN recorderState=$state keyboardShown=$keyboardShown " +
                "keyboardSwitch=$switchOn policy=$changed owned=$owned"
        )
    }

    fun note(prefs: Prefs, text: String) {
        if (prefs.getBoolean(KEY_DIAG_LOG, false)) XposedBridge.log("$TAG [EssentialDiag] u=${SystemClock.uptimeMillis()} $text")
    }

    private fun snapshot(): String {
        val app = AndroidAppHelper.currentApplication() ?: return "no context"
        val foreground = (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses
            ?.filter { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            ?.map { it.processName }
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked
        return "foreground=$foreground keyguardLocked=$locked"
    }

    private fun emit(text: String) {
        busy.set(true)
        try {
            XposedBridge.log("$TAG [EssentialDiag] u=${SystemClock.uptimeMillis()} $text")
        } finally {
            busy.set(false)
        }
    }
}
