package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.app.Application
import android.provider.Settings
import android.util.Log
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.Executors

/**
 * Runs inside Essential Recorder (com.nothing.ntessentialrecorder) and publishes the state of its capture flow,
 * which the Essential key switches in [EssentialKeyHooks] need (capture bar open or not).
 *
 * The Recorder logs every transition as `Log.d(tag, "setEssentialState, from X, to Y")`. That log line is read
 * here (framework API + message text, so it does not depend on the app's obfuscated class names) and the new
 * state is written to Settings.Secure[KEY_ES_STATE], which system_server observes.
 * States seen on device: IDLE, PREPARING, PENDING_STARTUP, STARTUP, STARTUP_SCALING, PENDING_FOCUS, FOCUS,
 * FOCUS_SHARE, PENDING_SAVE, FOCUS_SAVING, PENDING_EXIT.
 */
class EssentialRecorderHooks : HookModule {

    companion object {
        const val PKG = "com.nothing.ntessentialrecorder"
        private val STATE_RE = Regex("""^setEssentialState, from \w+, to (\w+)""")
    }

    private var published = "" // only touched on [writer]
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "NtTweaksEsState").apply { isDaemon = true } }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != PKG) return
        try {
            XposedBridge.hookAllMethods(Log::class.java, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val msg = param.args.getOrNull(1) as? String ?: return
                    if (!msg.startsWith("setEssentialState, from ")) return
                    STATE_RE.find(msg)?.let { publish(it.groupValues[1]) }
                }
            })
            EssentialDiag.install(prefs) // TEMPORARY, remove before the PR
            // a fresh Recorder process starts in IDLE; this also clears a state left behind by a killed process
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    publish("IDLE")
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Recorder hooks failed: $t")
        }
    }

    private fun publish(state: String) {
        writer.execute {
            if (published == state) return@execute
            try {
                val app = AndroidAppHelper.currentApplication() ?: return@execute
                Settings.Secure.putString(app.contentResolver, EssentialKeyHooks.KEY_ES_STATE, state)
                published = state
            } catch (t: Throwable) {
                // e.g. SecurityException if the Recorder ever stops running as the system uid
                XposedBridge.log("$TAG [EssentialKey] publishing the Recorder state failed: $t")
            }
        }
    }
}
