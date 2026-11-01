package com.souleven.nothingos.hooks

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/** Hides only the status-bar volume/ringer icon. */
class SilentIconHooks : HookModule {
    companion object {
        private const val PREF_KEY = "pref_hide_silent_icon"
        private const val POLICY_CLASS =
            "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val policies = WeakHashMap<Any, Boolean>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val policyClass = XposedHelpers.findClassIfExists(POLICY_CLASS, lpparam.classLoader)
        if (policyClass == null) {
            XposedBridge.log("NothingTweaks: [SilentIcon] PhoneStatusBarPolicy not found")
            return
        }

        try {
            XposedBridge.hookAllMethods(policyClass, "updateVolumeZen", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val policy = param.thisObject ?: return
                    synchronized(policies) { policies[policy] = true }
                    applyVisibility(policy, prefs)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [SilentIcon] Failed to hook updateVolumeZen: ${t.message}")
            XposedBridge.log(t)
        }

        prefs.addListener {
            mainHandler.post {
                val instances = synchronized(policies) { policies.keys.toList() }
                instances.forEach { policy -> applyVisibility(policy, prefs) }
            }
        }
    }

    private fun applyVisibility(policy: Any, prefs: Prefs) {
        if (!prefs.getBoolean(PREF_KEY, false)) return
        try {
            val iconController = XposedHelpers.getObjectField(policy, "mIconController") ?: return
            // Android's status-bar icon slot for the ringer/volume indicator is "volume".
            XposedHelpers.callMethod(iconController, "setIconVisibility", "volume", false)
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [SilentIcon] Failed to hide volume icon: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
