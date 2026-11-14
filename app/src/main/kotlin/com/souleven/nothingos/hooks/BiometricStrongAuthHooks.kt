package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Keeps face and fingerprint unlock from being switched off by the
 * "unattended update" strong-auth requirement.
 *
 * Resume-on-Reboot preparation (RecoverySystemService.requestLskf) calls
 * LockSettingsStrongAuth.requireStrongAuth(STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE, USER_ALL)
 * shortly after unlocking. That flag disables every biometric until the PIN is entered
 * again, which shows the "extra security" PIN screen.
 *
 * Only that one bit is dropped. A request that is exactly this flag is skipped; other
 * flags (such as the one required after a reboot) are never touched. A request is never
 * turned into 0, because requireStrongAuth(0, ..) clears every flag.
 */
class BiometricStrongAuthHooks : HookModule {

    companion object {
        const val KEY_KEEP_BIOMETRIC_UNLOCK = "pref_keep_biometric_unlock"
        private const val CLASS_STRONG_AUTH =
            "com.android.server.locksettings.LockSettingsStrongAuth"
        private const val CLASS_TRACKER =
            "com.android.internal.widget.LockPatternUtils\$StrongAuthTracker"
        private const val FIELD_UNATTENDED_UPDATE = "STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return

        val clazz = XposedHelpers.findClassIfExists(CLASS_STRONG_AUTH, lpparam.classLoader)
        if (clazz == null) {
            XposedBridge.log("$TAG [BiometricStrongAuth] LockSettingsStrongAuth not found")
            return
        }

        // Read the constant from the framework instead of hardcoding it.
        val flag = try {
            val tracker = XposedHelpers.findClass(CLASS_TRACKER, lpparam.classLoader)
            XposedHelpers.getStaticIntField(tracker, FIELD_UNATTENDED_UPDATE)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BiometricStrongAuth] $FIELD_UNATTENDED_UPDATE not found: ${t.message}")
            return
        }
        if (flag == 0) return

        try {
            val hooked = XposedBridge.hookAllMethods(clazz, "requireStrongAuth", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(KEY_KEEP_BIOMETRIC_UNLOCK, false)) return
                    val reason = param.args.getOrNull(0) as? Int ?: return
                    if (reason and flag == 0) return
                    val rest = reason and flag.inv()
                    if (rest == 0) {
                        param.result = null
                    } else {
                        param.args[0] = rest
                    }
                }
            })
            if (hooked.isEmpty()) {
                XposedBridge.log("$TAG [BiometricStrongAuth] requireStrongAuth not found")
            } else {
                XposedBridge.log("$TAG [BiometricStrongAuth] Hooked LockSettingsStrongAuth#requireStrongAuth")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BiometricStrongAuth] FAILED to hook requireStrongAuth: ${t.message}")
        }
    }
}
