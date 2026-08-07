package cn.geektang.privacyspace.hook.impl

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.pm.*
import android.os.Build
import cn.geektang.privacyspace.hook.HookMain
import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.tryLoadClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SettingsAppHookImpl : Hooker, XC_MethodHook() {

    override fun start(classLoader: ClassLoader) {
        val packageManagerClass = try {
            classLoader.tryLoadClass("android.content.pm.IPackageManager\$Stub\$Proxy")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "SettingsAppHookImpl start failed.")
            return
        }
        for (method in packageManagerClass.declaredMethods) {
            when (method.name) {
                "getInstalledPackages", "getInstalledApplications", "getInstalledModules", "queryIntentActivities" -> {
                    XposedBridge.hookMethod(method, this)
                }
                else -> {}
            }
        }
        XposedHelpers.findAndHookMethod(
            UsageStatsManager::class.java,
            "queryUsageStats",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            this
        )
        XposedHelpers.findAndHookMethod(
            ActivityManager::class.java,
            "getRunningAppProcesses",
            this
        )
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val hiddenAppList = HookMain.configData.hiddenAppList
        when (param.method.name) {
            "getInstalledPackages" -> {
                val result = param.result as ParceledListSlice<PackageInfo>
                val iterator = result.list.iterator()
                while (iterator.hasNext()) {
                    if (hiddenAppList.contains(iterator.next().packageName)) {
                        iterator.remove()
                    }
                }
            }
            "getInstalledApplications" -> {
                val result = param.result as ParceledListSlice<ApplicationInfo>
                val iterator = result.list.iterator()
                while (iterator.hasNext()) {
                    if (hiddenAppList.contains(iterator.next().packageName)) {
                        iterator.remove()
                    }
                }
            }
            "getInstalledModules" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val result = param.result as MutableList<ModuleInfo>
                    val iterator = result.iterator()
                    while (iterator.hasNext()) {
                        if (hiddenAppList.contains(iterator.next().packageName)) {
                            iterator.remove()
                        }
                    }
                }
            }
            "queryIntentActivities" -> {
                val result = param.result as ParceledListSlice<ResolveInfo>
                val iterator = result.list.iterator()
                while (iterator.hasNext()) {
                    if (hiddenAppList.contains(iterator.next().getPackageName())) {
                        iterator.remove()
                    }
                }
            }
            "queryUsageStats" -> {
                val result = param.result as MutableList<UsageStats>
                val iterator = result.iterator()
                while (iterator.hasNext()) {
                    if (hiddenAppList.contains(iterator.next().packageName)) {
                        iterator.remove()
                    }
                }
            }
            "getRunningAppProcesses" -> {
                val result = param.result as? List<*> ?: return
                param.result = result.filter {
                    it as ActivityManager.RunningAppProcessInfo
                    var shouldFilter = false
                    it.pkgList.forEach { pkg ->
                        if (hiddenAppList.contains(pkg)) {
                            shouldFilter = true
                        }
                    }
                    !shouldFilter
                }
            }
            else -> {}
        }
    }
}
