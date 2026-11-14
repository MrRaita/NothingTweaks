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

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return

        val clazz = XposedHelpers.findClassIfExists(CLASS_STRONG_AUTH, lpparam.classLoader)
        if (clazz == null) {
            XposedBridge.log("$TAG [BiometricStrongAuth] LockSettingsStrongAuth not found")
            return
        }

        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
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

        for (name in METHODS) {
            try {
                val hooked = XposedBridge.hookAllMethods(clazz, name, callback)
                if (hooked.isEmpty()) {
                    XposedBridge.log("$TAG [BiometricStrongAuth] method not found: $name")
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [BiometricStrongAuth] FAILED to hook $name: ${t.message}")
            }
        }
    }
}
