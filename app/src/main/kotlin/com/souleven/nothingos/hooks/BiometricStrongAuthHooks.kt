package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Keeps non-strong biometrics (face unlock) from being switched off by the
 * "non-strong biometric" timeouts in LockSettingsStrongAuth.
 *
 * Only the allowed/not-allowed state of non-strong biometrics is touched.
 * The strong-auth flags (e.g. required after reboot) are left alone, so the
 * normal PIN prompt after a restart keeps working.
 */
class BiometricStrongAuthHooks : HookModule {

    companion object {
        const val KEY_KEEP_FACE_UNLOCK = "pref_keep_face_unlock"
        private const val CLASS_STRONG_AUTH =
            "com.android.server.locksettings.LockSettingsStrongAuth"

        // All take (boolean allowed, int userId)
        private val METHODS = arrayOf(
            "setIsNonStrongBiometricAllowed",
            "setIsNonStrongBiometricAllowedOneUser",
            "notifyStrongAuthTrackersForIsNonStrongBiometricAllowed"
        )
    }

    @Volatile
    private var loggedOverride = false

    // TEMPORARY diagnostics: remove before the PR.
    private val DIAG_METHODS = arrayOf(
        "requireStrongAuth",
        "reportSuccessfulBiometricUnlock",
        "reportSuccessfulStrongAuthUnlock",
        "handleScheduleNonStrongBiometricTimeout",
        "handleScheduleNonStrongBiometricIdleTimeout"
    )

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return

        val clazz = XposedHelpers.findClassIfExists(CLASS_STRONG_AUTH, lpparam.classLoader)
        if (clazz == null) {
            XposedBridge.log("$TAG [BiometricStrongAuth] LockSettingsStrongAuth not found")
            return
        }

        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.log("$TAG [BiometricStrongAuth][diag] ${param.method.name}(${param.args.joinToString(",")}) enabled=${prefs.getBoolean(KEY_KEEP_FACE_UNLOCK, false)}")
                if (!prefs.getBoolean(KEY_KEEP_FACE_UNLOCK, false)) return
                if (param.args.getOrNull(0) != false) return
                param.args[0] = true
                if (!loggedOverride) {
                    loggedOverride = true
                    XposedBridge.log(
                        "$TAG [BiometricStrongAuth] kept non-strong biometrics allowed " +
                            "(${param.method.name})"
                    )
                }
            }
        }

        val diag = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args.joinToString(",") { a ->
                    if (param.method.name == "requireStrongAuth" && a is Int) "0x" + Integer.toHexString(a) else a.toString()
                }
                val caller = Throwable().stackTrace
                    .drop(3).take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                XposedBridge.log("$TAG [BiometricStrongAuth][diag] ${param.method.name}($args) enabled=${prefs.getBoolean(KEY_KEEP_FACE_UNLOCK, false)} from $caller")
            }
        }

        for (name in DIAG_METHODS) {
            try {
                val hooked = XposedBridge.hookAllMethods(clazz, name, diag)
                XposedBridge.log("$TAG [BiometricStrongAuth][diag] hooked $name x${hooked.size}")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [BiometricStrongAuth][diag] FAILED $name: ${t.message}")
            }
        }

        hookUnattendedUpdateFlag(clazz, lpparam.classLoader, prefs)

        for (name in METHODS) {
            try {
                val hooked = XposedBridge.hookAllMethods(clazz, name, callback)
                XposedBridge.log("$TAG [BiometricStrongAuth] hooked $name x${hooked.size}")
                if (hooked.isEmpty()) {
                    XposedBridge.log("$TAG [BiometricStrongAuth] method not found: $name")
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [BiometricStrongAuth] FAILED to hook $name: ${t.message}")
            }
        }
    }

    /**
     * Resume-on-Reboot preparation (RecoverySystemService.requestLskf) asks for
     * STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE for all users, which disables
     * every biometric until the PIN is entered. Drop only that bit.
     *
     * A request that is exactly this flag is skipped. Never turn a request into 0:
     * requireStrongAuth(0, ..) clears ALL flags (including the one after reboot).
     */
    private fun hookUnattendedUpdateFlag(clazz: Class<*>, cl: ClassLoader, prefs: Prefs) {
        val flag = try {
            val tracker = XposedHelpers.findClass(
                "com.android.internal.widget.LockPatternUtils\$StrongAuthTracker", cl
            )
            XposedHelpers.getStaticIntField(tracker, "STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BiometricStrongAuth] unattended-update flag not found: ${t.message}")
            return
        }
        XposedBridge.log(
            "$TAG [BiometricStrongAuth] STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE=0x" +
                Integer.toHexString(flag)
        )
        if (flag == 0) return

        try {
            XposedBridge.hookAllMethods(clazz, "requireStrongAuth", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(KEY_KEEP_FACE_UNLOCK, false)) return
                    val reason = param.args.getOrNull(0) as? Int ?: return
                    if (reason and flag == 0) return
                    val rest = reason and flag.inv()
                    if (rest == 0) {
                        param.result = null
                    } else {
                        param.args[0] = rest
                    }
                    XposedBridge.log(
                        "$TAG [BiometricStrongAuth] dropped unattended-update flag " +
                            "(0x${Integer.toHexString(reason)} -> 0x${Integer.toHexString(rest)})"
                    )
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BiometricStrongAuth] FAILED to hook requireStrongAuth: ${t.message}")
        }
    }
}
