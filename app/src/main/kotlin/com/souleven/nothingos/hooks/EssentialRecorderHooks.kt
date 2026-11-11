package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.app.Application
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.Executors

/**
 * Runs inside Essential Recorder (com.nothing.ntessentialrecorder).
 *
 * 1. State publisher (needed by the Essential key switches in EssentialKeyHooks).
 *    The Recorder logs every capture-flow transition as `Log.d(tag, "setEssentialState, from X, to Y")`. That log
 *    line is read here (framework API + message text, so it does not depend on the app's obfuscated class names)
 *    and the new state is written to Settings.Secure[KEY_ES_STATE], which system_server reads when a key press
 *    starts. States seen on device: IDLE, PREPARING, PENDING_STARTUP, STARTUP, STARTUP_SCALING, PENDING_FOCUS,
 *    FOCUS, FOCUS_SHARE, PENDING_SAVE, FOCUS_SAVING, PENDING_EXIT.
 *
 * 2. Screenshot sharing: with the capture bar open, a hold on the key (decided in EssentialKeyHooks) asks this process,
 *    through Settings.Secure[KEY_ES_SHARE_REQUEST], to send the current screenshot to a chosen app. The Recorder
 *    takes its screenshot with ScreenCapture and reads it through `ScreenshotHardwareBuffer.asBitmap()`; a copy is
 *    kept (only while the feature is on) and written to MediaStore (Pictures/EssentialKey/, one file, replaced on
 *    every share) so it can be handed over with ACTION_SEND and a read grant.
 *
 * 3. DIAGNOSTIC (safe to remove): logs MediaStore writes and Bitmap.compress calls together with the current
 *    state, to find out how the screenshot is stored. Nothing is logged while the debug log switch is off.
 */
class EssentialRecorderHooks : HookModule {

    companion object {
        const val PKG = "com.nothing.ntessentialrecorder"
        private val STATE_RE = Regex("""^setEssentialState, from (\w+), to (\w+)""")
        private const val SHARE_NAME = "essential_capture.png"
        private const val SHARE_PATH = "Pictures/EssentialKey"
        private val INTERESTING_VALUES = setOf(
            "_display_name", "title", "mime_type", "relative_path", "is_pending", "owner_package_name", "_size"
        )
    }

    @Volatile private var state = "IDLE"
    private var published = "" // only touched on [writer]
    private lateinit var prefs: Prefs
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "NtTweaksEsState").apply { isDaemon = true } }
    private val insertGuard = ThreadLocal<Boolean>()

    @Volatile private var lastCapture: Bitmap? = null // software copy of the newest screenshot (only while sharing is on)
    private var shareObserverRegistered = false
    private var lastShareRequest = ""
    private val shareExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "NtTweaksEsShare").apply { isDaemon = true } }

    /** Lazy log: the message is only built when the debug log is on. */
    private inline fun d(area: String, msg: () -> String) {
        if (EssentialKeyHooks.debugEnabled(prefs)) EssentialKeyHooks.log(prefs, area, "[recorder] ${msg()}")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != PKG) return
        this.prefs = prefs

        hookStateLog()
        hookProcessStart()
        hookScreenshotBitmap(lpparam.classLoader)
        hookMediaStore()
        XposedBridge.log("$TAG [EssentialKey][hook] Recorder hooks installed (rev ${EssentialKeyHooks.REV})")
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

    /** A fresh Recorder process starts in IDLE; this also clears a state left behind by a killed process. */
    private fun hookProcessStart() {
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    publish("IDLE", "process start")
                    (param.thisObject as? Application)?.let { registerShareObserver(it) }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] Recorder Application.onCreate hook failed: $t")
        }
    }

    private fun publish(newState: String, why: String) {
        state = newState
        if (newState == "IDLE") lastCapture = null // the capture flow is over, drop the screenshot copy
        writer.execute {
            if (published == newState) return@execute
            try {
                val app = AndroidAppHelper.currentApplication() ?: return@execute
                Settings.Secure.putString(app.contentResolver, EssentialKeyHooks.KEY_ES_STATE, newState)
                d("es") { "state $published -> $newState ($why)" }
                published = newState
            } catch (t: Throwable) {
                // e.g. SecurityException if the Recorder ever stops running as the system uid
                XposedBridge.log("$TAG [EssentialKey][err] publishing state failed: $t")
            }
        }
    }

    // ---------------------------------------------------------------- screenshot sharing

    private fun shareConfigured() =
        prefs.getBoolean(EssentialKeyHooks.KEY_SHARE_ENABLED, false) &&
            prefs.getString(EssentialKeyHooks.KEY_SHARE_APP, "").isNotEmpty()

    /** Keeps a software copy of every screenshot the Recorder takes, but only while sharing is switched on. */
    private fun hookScreenshotBitmap(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("android.window.ScreenCapture\$ScreenshotHardwareBuffer", cl)
        if (cls == null) {
            XposedBridge.log("$TAG [EssentialKey][hook] ScreenshotHardwareBuffer not found, screenshot sharing disabled")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "asBitmap", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!shareConfigured()) {
                            d("share") {
                                "screenshot taken, sharing off (enabled=${prefs.getBoolean(EssentialKeyHooks.KEY_SHARE_ENABLED, false)} " +
                                    "app='${prefs.getString(EssentialKeyHooks.KEY_SHARE_APP, "")}')"
                            }
                            return
                        }
                        val bmp = param.result as? Bitmap ?: return
                        lastCapture = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        d("share") { "kept ${bmp.width}x${bmp.height} screenshot copy for sharing (state=$state)" }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG [EssentialKey][err] keeping screenshot copy failed: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][hook] asBitmap hook failed: $t")
        }
    }

    private fun registerShareObserver(app: Application) {
        if (shareObserverRegistered) return
        shareObserverRegistered = true
        try {
            val resolver = app.contentResolver
            lastShareRequest = Settings.Secure.getString(resolver, EssentialKeyHooks.KEY_ES_SHARE_REQUEST) ?: ""
            val uri = Settings.Secure.getUriFor(EssentialKeyHooks.KEY_ES_SHARE_REQUEST)
            resolver.registerContentObserver(uri, false, object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    onShareRequest(app)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] share observer failed: $t")
        }
    }

    private fun onShareRequest(app: Application) {
        try {
            val value = Settings.Secure.getString(app.contentResolver, EssentialKeyHooks.KEY_ES_SHARE_REQUEST) ?: return
            if (value.isEmpty() || value == lastShareRequest) return
            lastShareRequest = value
            val pkg = value.substringBefore('|')
            prefs.forceReload()
            // only ever send to the app the user picked, whatever the request says
            if (!shareConfigured() || pkg != prefs.getString(EssentialKeyHooks.KEY_SHARE_APP, "")) {
                d("share") { "request for '$pkg' ignored (not the configured app)" }
                return
            }
            shareExecutor.execute { shareCapture(app, pkg) }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] share request failed: $t")
        }
    }

    private fun shareCapture(app: Application, pkg: String) {
        val bmp = lastCapture
        if (bmp == null) {
            d("share") { "no screenshot copy kept (state=$state); was sharing switched on before the capture?" }
            return
        }
        try {
            val resolver = app.contentResolver
            val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // one file only: drop the previous share
            resolver.delete(
                images,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(SHARE_NAME, "$SHARE_PATH/")
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, SHARE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, SHARE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(images, values)
            if (uri == null) {
                d("share") { "MediaStore insert returned null" }
                return
            }
            val written = resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            if (!written) {
                d("share") { "writing the screenshot failed" }
                return
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(send)
            d("share") { "sent ${bmp.width}x${bmp.height} screenshot to $pkg as $uri" }
        } catch (t: Throwable) {
            // e.g. ActivityNotFoundException when the app does not accept images
            XposedBridge.log("$TAG [EssentialKey][err] sharing the screenshot to $pkg failed: $t")
        }
    }

    // ---------------------------------------------------------------- MediaStore / bitmap diagnostics

    private fun isMedia(uri: Uri?) = uri != null && uri.authority == "media"

    private fun describe(v: ContentValues?): String =
        if (v == null) "null" else v.keySet().filter { it in INTERESTING_VALUES }.joinToString(", ", "{", "}") { "$it=${v.get(it)}" }

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
                    if (isMedia(uri)) d("ms") { "insert uri=$uri values=${describe(param.args.getOrNull(1) as? ContentValues)} state=$state" }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (insertGuard.get() != true) return
                    insertGuard.set(false)
                    val uri = param.args.getOrNull(0) as? Uri
                    if (isMedia(uri)) d("ms") { "insert result=${param.result} throwable=${param.throwable}" }
                }
            })
            XposedBridge.hookAllMethods(cr, "update", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uri = param.args.getOrNull(0) as? Uri
                    if (isMedia(uri)) d("ms") { "update uri=$uri values=${describe(param.args.getOrNull(1) as? ContentValues)} state=$state" }
                }
            })
            for (name in listOf("openOutputStream", "openFileDescriptor", "openAssetFileDescriptor")) {
                XposedBridge.hookAllMethods(cr, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uri = param.args.getOrNull(0) as? Uri
                        if (isMedia(uri)) d("ms") { "$name uri=$uri mode=${param.args.getOrNull(1)} state=$state" }
                    }
                })
            }
            XposedBridge.hookAllMethods(Bitmap::class.java, "compress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bmp = param.thisObject as? Bitmap ?: return
                    d("ms") {
                        "Bitmap.compress format=${param.args.getOrNull(0)} quality=${param.args.getOrNull(1)} " +
                            "${bmp.width}x${bmp.height} stream=${param.args.getOrNull(2)?.javaClass?.name} state=$state from ${callers()}"
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey][err] Recorder MediaStore hooks failed: $t")
        }
    }
}
