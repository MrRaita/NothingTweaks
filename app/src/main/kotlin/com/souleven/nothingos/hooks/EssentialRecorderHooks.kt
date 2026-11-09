package com.souleven.nothingos.hooks

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.Executors

/**
 * Runs inside Essential Recorder (com.nothing.ntessentialrecorder).
 *
 * 1. Publishes the Recorder's capture-flow state for the Essential key remap (EssentialKeyHooks).
 *    The Recorder logs every transition as `Log.d("u", "setEssentialState, from X, to Y")`. We read that log
 *    line (framework API + message text, so it does not depend on the app's obfuscated class names) and write the
 *    new state to Settings.Secure[KEY_ES_STATE], which system_server reads when a key press starts.
 *    States seen: IDLE, PREPARING, PENDING_STARTUP, STARTUP, STARTUP_SCALING, PENDING_FOCUS, FOCUS,
 *    FOCUS_SHARE, PENDING_SAVE, FOCUS_SAVING, PENDING_EXIT.
 * 2. Diagnostics: logs MediaStore writes and Bitmap.compress calls (with the current state) to find out how the
 *    screenshot is stored, for sending it to another app later.
 */
class EssentialRecorderHooks : HookModule {

    companion object {
        const val PKG = "com.nothing.ntessentialrecorder"
        private val STATE_RE = Regex("""^setEssentialState, from (\w+), to (\w+)""")
        private val INTERESTING = setOf(
            "_display_name", "title", "mime_type", "relative_path", "is_pending", "owner_package_name", "_size"
        )
    }

    @Volatile private var state = "IDLE"
    @Volatile private var published = ""
    private lateinit var prefs: Prefs
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "NtTweaksEsState").apply { isDaemon = true } }

    private val insertGuard = ThreadLocal<Boolean>()

    private fun d(area: String, msg: String) = EssentialKeyHooks.log(prefs, area, "[recorder] $msg")

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != PKG) return
        this.prefs = prefs

        hookStateLog()
        hookProcessStart()
        hookMediaStore()
        XposedBridge.log("$TAG [EssentialKey][hook] Recorder hooks installed (state publisher, MediaStore log)")
    }

    // ---------------------------------------------------------------- state publisher

    private fun hookStateLog() {
        try {
            XposedBridge.hookAllMethods(Log::class.java, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val msg = param.args.getOrNull(1) as? String ?: return
                    if (!msg.startsWith("setEssentialState, from ")) return
                    val m = STATE_RE.find(msg) ?: return
                    publish(m.groupValues[2], "transition ${m.groupValues[1]} -> ${m.groupValues[2]}")
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] Recorder Log.d hook failed: $t")
        }
    }

    /** A fresh Recorder process starts in IDLE; clears a state left behind by a killed process. */
    private fun hookProcessStart() {
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    publish("IDLE", "process start")
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] Recorder Application.onCreate hook failed: $t")
        }
    }

    private fun publish(newState: String, why: String) {
        state = newState
        writer.execute {
            if (published == newState) return@execute
            try {
                val app = AndroidAppHelper.currentApplication() ?: return@execute
                Settings.Secure.putString(app.contentResolver, EssentialKeyHooks.KEY_ES_STATE, newState)
                d("es", "state $published -> $newState ($why)")
                published = newState
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [EssentialKey][err] publishing state failed: $t")
            }
        }
    }

    // ---------------------------------------------------------------- MediaStore / bitmap diagnostics

    private fun isMedia(uri: Uri?) = uri != null && uri.authority == "media"

    private fun describe(v: ContentValues?): String =
        if (v == null) "null" else v.keySet().filter { it in INTERESTING }.joinToString(", ", "{", "}") { "$it=${v.get(it)}" }

    private fun callers(): String =
        Throwable().stackTrace.drop(3).take(5).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }

    private fun hookMediaStore() {
        try {
            val cr = ContentResolver::class.java
            XposedBridge.hookAllMethods(cr, "insert", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (insertGuard.get() == true) return // insert(Uri, ContentValues) delegates to the Bundle overload
                    insertGuard.set(true)
                    val uri = param.args.getOrNull(0) as? Uri
                    if (isMedia(uri)) {
                        d("ms", "insert uri=$uri values=${describe(param.args.getOrNull(1) as? ContentValues)} state=$state")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (insertGuard.get() != true) return
                    insertGuard.set(false)
                    val uri = param.args.getOrNull(0) as? Uri
                    if (isMedia(uri)) d("ms", "insert result=${param.result} throwable=${param.throwable}")
                }
            })
            XposedBridge.hookAllMethods(cr, "update", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uri = param.args.getOrNull(0) as? Uri
                    if (isMedia(uri)) {
                        d("ms", "update uri=$uri values=${describe(param.args.getOrNull(1) as? ContentValues)} state=$state")
                    }
                }
            })
            for (name in listOf("openOutputStream", "openFileDescriptor", "openAssetFileDescriptor")) {
                XposedBridge.hookAllMethods(cr, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uri = param.args.getOrNull(0) as? Uri
                        if (isMedia(uri)) d("ms", "$name uri=$uri mode=${param.args.getOrNull(1)} state=$state")
                    }
                })
            }
            XposedBridge.hookAllMethods(Bitmap::class.java, "compress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bmp = param.thisObject as? Bitmap ?: return
                    d(
                        "ms",
                        "Bitmap.compress format=${param.args.getOrNull(0)} quality=${param.args.getOrNull(1)} " +
                            "${bmp.width}x${bmp.height} stream=${param.args.getOrNull(2)?.javaClass?.name} " +
                            "state=$state from ${callers()}"
                    )
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] Recorder MediaStore hooks failed: $t")
        }
    }
}
