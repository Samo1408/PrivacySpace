package cn.geektang.privacyspace.hook.impl

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.pm.*
import android.os.Build
import cn.geektang.privacyspace.hook.HookMain
import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.ConfigHelper.getPackageName
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.tryLoadClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker

object SettingsAppHookImpl : Hooker {

    override fun start(classLoader: ClassLoader) {
        val packageManagerClass = try {
            classLoader.tryLoadClass("android.content.pm.IPackageManager\$Stub\$Proxy")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "SettingsAppHookImpl start failed.")
            return
        }
        XLog.i("Hook class packageManagerClass.")
        for (method in packageManagerClass.declaredMethods) {
            when (method.name) {
                "getInstalledPackages", "getInstalledApplications", "getInstalledModules", "queryIntentActivities" -> {
                    XLog.d("Hook method ${method.name}")
                    XposedModule.hook(method, SettingsPackageManagerMethodHooker::class.java)
                }
                else -> {}
            }
        }

        // Hook UsageStatsManager.queryUsageStats
        try {
            val queryUsageStatsMethod = UsageStatsManager::class.java.getDeclaredMethod(
                "queryUsageStats",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            XposedModule.hook(queryUsageStatsMethod, UsageStatsHooker::class.java)
        } catch (_: Exception) {}

        // Hook ActivityManager.getRunningAppProcesses
        try {
            val getRunningAppProcessesMethod = ActivityManager::class.java.getDeclaredMethod("getRunningAppProcesses")
            XposedModule.hook(getRunningAppProcessesMethod, SettingsRunningAppProcessesHooker::class.java)
        } catch (_: Exception) {}
    }

    @XposedHooker
    class SettingsPackageManagerMethodHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val hiddenAppList = HookMain.configData.hiddenAppList
                val result = callback.result
                when {
                    result is List<*> -> {
                        callback.result = result.filter { item ->
                            val packageName = when (item) {
                                is PackageInfo -> item.packageName
                                is ApplicationInfo -> item.packageName
                                is ModuleInfo -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) item.packageName else null
                                is ResolveInfo -> item.getPackageName()
                                else -> null
                            }
                            packageName == null || !hiddenAppList.contains(packageName)
                        }
                    }
                }
            }
        }
    }

    @XposedHooker
    class UsageStatsHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val result = callback.result as? MutableList<UsageStats> ?: return
                val hiddenAppList = HookMain.configData.hiddenAppList
                val iterator = result.iterator()
                while (iterator.hasNext()) {
                    val usageStats = iterator.next()
                    if (hiddenAppList.contains(usageStats.packageName)) {
                        iterator.remove()
                        XLog.i("com.android.settings was prevented from reading ${usageStats.packageName}.")
                    }
                }
            }
        }
    }

    @XposedHooker
    class SettingsRunningAppProcessesHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val result = callback.result as? List<*> ?: return
                val hiddenAppList = HookMain.configData.hiddenAppList
                callback.result = result.filter {
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
        }
    }
}
