package com.souleven.nothingos.hooks

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Hides only the mute (silent-ringer) status-bar icon. */
class SilentIconHooks : HookModule {
    companion object {
        private const val PREF_KEY = "pref_hide_silent_icon"
        private const val POLICY_CLASS =
            "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy"
        private const val UPDATE_METHOD = "updateRingerAndAlarmIcons"
        private const val ICON_CONTROLLER_FIELD = "mIconController"
        private const val MUTE_SLOT_FIELD = "mSlotMute"
        private const val MUTE_VISIBLE_FIELD = "mMuteVisible"

        private val hookLock = Any()
        // Track the actual Class, not a process-wide Boolean: distinct ClassLoaders
        // can legitimately define distinct PhoneStatusBarPolicy classes.
        private val processedClasses = WeakHashMap<Class<*>, Unit>()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val policies = WeakHashMap<Any, Unit>()
    private val visibilityErrorLogged = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val policyClass = XposedHelpers.findClassIfExists(POLICY_CLASS, lpparam.classLoader)
            ?: return

        synchronized(hookLock) {
            if (processedClasses.containsKey(policyClass)) return

            val hooks = try {
                XposedBridge.hookAllMethods(
                    policyClass,
                    UPDATE_METHOD,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val policy = param.thisObject ?: return
                            synchronized(policies) { policies[policy] = Unit }
                            applyVisibility(policy, prefs)
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log(
                    "NothingTweaks: [SilentIcon] Failed to hook $POLICY_CLASS#$UPDATE_METHOD: ${t.message}"
                )
                XposedBridge.log(t)
                return
            }

            if (hooks.isEmpty()) {
                // A Class is immutable; retrying the same missing method on later
                // package callbacks cannot succeed and would only repeat reflection/logs.
                processedClasses[policyClass] = Unit
                XposedBridge.log(
                    "NothingTweaks: [SilentIcon] $UPDATE_METHOD not found in $POLICY_CLASS"
                )
                return
            }

            processedClasses[policyClass] = Unit
            XposedBridge.log(
                "NothingTweaks: [SilentIcon] Hooked $POLICY_CLASS#$UPDATE_METHOD (${hooks.size} overload(s))"
            )
        }

        if (listenerRegistered.compareAndSet(false, true)) {
            prefs.addListener {
                mainHandler.post {
                    val instances = synchronized(policies) { policies.keys.toList() }
                    instances.forEach { policy -> applyVisibility(policy, prefs) }
                }
            }
        }
    }

    private fun applyVisibility(policy: Any, prefs: Prefs) {
        try {
            val iconController = XposedHelpers.getObjectField(policy, ICON_CONTROLLER_FIELD)
                ?: throw IllegalStateException("$ICON_CONTROLLER_FIELD is null")
            val muteSlot = XposedHelpers.getObjectField(policy, MUTE_SLOT_FIELD) as? String
                ?: throw IllegalStateException("$MUTE_SLOT_FIELD is null or not a String")
            val muteVisible = XposedHelpers.getBooleanField(policy, MUTE_VISIBLE_FIELD)
            val visible = muteVisible && !prefs.getBooleanCached(PREF_KEY, false)

            XposedHelpers.callMethod(iconController, "setIconVisibility", muteSlot, visible)
        } catch (t: Throwable) {
            if (visibilityErrorLogged.compareAndSet(false, true)) {
                XposedBridge.log(
                    "NothingTweaks: [SilentIcon] Failed to update mute icon visibility: ${t.message}"
                )
                XposedBridge.log(t)
            }
        }
    }
}
