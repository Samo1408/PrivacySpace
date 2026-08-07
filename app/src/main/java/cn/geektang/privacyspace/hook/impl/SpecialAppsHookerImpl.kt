package cn.geektang.privacyspace.hook.impl

import android.app.ActivityManager
import android.content.Context
import android.content.pm.*
import android.os.Build
import cn.geektang.privacyspace.hook.HookMain
import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.ConfigHelper.getPackageName
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.loadClassSafe
import cn.geektang.privacyspace.util.tryLoadClass
import com.android.internal.os.BatterySipper
import com.android.internal.os.BatteryStatsHelper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Constructor

object SpecialAppsHookerImpl : Hooker {
    override fun start(classLoader: ClassLoader) {
        val packageManagerClass = try {
            classLoader.tryLoadClass("android.app.ApplicationPackageManager")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "SpecialAppsHookerImpl start failed.")
            return
        }

        val hookMethodSet =
            setOf(
                "getInstalledPackages",
                "getInstalledApplications",
                "getInstalledModules",
            )
        packageManagerClass.declaredMethods.forEach {
            if (hookMethodSet.contains(it.name)) {
                XposedModule.hook(it, PackageManagerMethodHooker::class.java)
            }
        }

        // Hook BatteryStatsHelper.getUsageList
        try {
            val getUsageListMethod = BatteryStatsHelper::class.java.getDeclaredMethod("getUsageList")
            XposedModule.hook(getUsageListMethod, BatteryStatsHooker::class.java)
        } catch (_: Exception) {}

        try {
            val getMobilemsppListMethod = BatteryStatsHelper::class.java.getDeclaredMethod("getMobilemsppList")
            XposedModule.hook(getMobilemsppListMethod, BatteryStatsHooker::class.java)
        } catch (_: Exception) {}

        // Hook ActivityManager.getRunningAppProcesses
        try {
            val getRunningAppProcessesMethod = ActivityManager::class.java.getDeclaredMethod("getRunningAppProcesses")
            XposedModule.hook(getRunningAppProcessesMethod, RunningAppProcessesHooker::class.java)
        } catch (_: Exception) {}

        val miuiRvClass = classLoader.loadClassSafe("miuix.recyclerview.widget.RecyclerView")
        if (miuiRvClass != null) {
            var unhook: XposedInterface.Unhook? = null
            try {
                unhook = XposedModule.hook(
                    miuiRvClass.getDeclaredConstructor(Context::class.java),
                    GameBoosterHooker::class.java
                )
            } catch (_: Exception) {}
        }

        val dockAppEditActivityClass =
            classLoader.loadClassSafe("com.miui.dock.edit.DockAppEditActivity")
        if (null != dockAppEditActivityClass) {
            for (method in dockAppEditActivityClass.declaredMethods) {
                if (method.parameterCount == 1 && method.parameterTypes.first() == PackageInfo::class.java) {
                    XposedModule.hook(method, DockAppEditHooker::class.java)
                }
            }
        }
    }

    @XposedHooker
    class PackageManagerMethodHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val shouldFilterAppList = HookMain.configData.hiddenAppList
                val result = callback.result as? List<*> ?: return
                callback.result = result.filter {
                    val packageName = when (it) {
                        is PackageInfo -> it.packageName
                        is ApplicationInfo -> it.packageName
                        is ModuleInfo -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.packageName else null
                        is ResolveInfo -> it.getPackageName()
                        else -> null
                    }
                    packageName == null || !shouldFilterAppList.contains(packageName)
                }
            }
        }
    }

    @XposedHooker
    class BatteryStatsHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val result = callback.result as? MutableList<*> ?: return
                val shouldFilterAppList = HookMain.configData.hiddenAppList
                val iterator = result.iterator()
                while (iterator.hasNext()) {
                    val batterySipper = (iterator.next() as? BatterySipper) ?: continue
                    val packages = batterySipper.packages
                    if (packages.isNullOrEmpty()) {
                        continue
                    }
                    for (packageName in packages) {
                        if (shouldFilterAppList.contains(packageName)) {
                            iterator.remove()
                            break
                        }
                    }
                }
            }
        }
    }

    @XposedHooker
    class RunningAppProcessesHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val result = callback.result as? List<*> ?: return
                val shouldFilterAppList = HookMain.configData.hiddenAppList
                callback.result = result.filter {
                    it as ActivityManager.RunningAppProcessInfo
                    var shouldFilter = false
                    it.pkgList.forEach { pkg ->
                        if (shouldFilterAppList.contains(pkg)) {
                            shouldFilter = true
                        }
                    }
                    !shouldFilter
                }
            }
        }
    }

    @XposedHooker
    class DockAppEditHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun beforeHookedMethod(callback: XposedInterface.HookerCallback) {
                val shouldFilterAppList = HookMain.configData.hiddenAppList
                val packageInfo = callback.args.first() as PackageInfo
                if (shouldFilterAppList.contains(packageInfo.packageName)) {
                    callback.result = Unit
                }
            }
        }
    }

    class GameBoosterHooker : XposedInterface.Hooker {
        private var list: MutableList<*>? = null

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val param = chain.args
            val thisClass = param.first()?.javaClass
            val result = chain.proceed()

            if (chain.executable is Constructor<*>) {
                if (!thisClass?.name?.startsWith("com.miui.gamebooster.windowmanager.")!!) {
                    return result
                }
                XLog.d("hook class $thisClass")
                for (method in thisClass.declaredMethods) {
                    if (method.name != "setDockType"
                        && method.parameterCount == 1
                        && method.parameterTypes.first() == Int::class.javaPrimitiveType
                    ) {
                        XposedModule.hook(method, GameBoosterSetDockTypeHooker::class.java)
                    }
                }
            } else {
                XLog.d("${param.first()?.javaClass} ${chain.executable.name}() invoke")
                queryAndGetList(thisClass)
                val listObj = list ?: return result
                val iterator = listObj.iterator()
                val shouldFilterAppList = HookMain.configData.hiddenAppList
                while (iterator.hasNext()) {
                    val appInfo = iterator.next() ?: continue
                    val appInfoClass = appInfo.javaClass

                    val shouldFilter = shouldFilter(appInfoClass, appInfo, shouldFilterAppList)
                    if (shouldFilter) {
                        iterator.remove()
                    }
                }
            }
            return result
        }

        private fun shouldFilter(
            appInfoClass: Class<Any>,
            appInfo: Any,
            shouldFilterAppList: Set<String>,
        ): Boolean {
            for (declaredField in appInfoClass.declaredFields) {
                declaredField.isAccessible = true
                val packageName = declaredField.get(appInfo)?.getPackageName() ?: continue
                if (packageName.isNotBlank() && shouldFilterAppList.contains(packageName)) {
                    XLog.d("filter class $packageName")
                    return true
                }
            }
            return false
        }

        private fun queryAndGetList(thisClass: Class<Any>) {
            for (declaredField in thisClass.declaredFields) {
                declaredField.isAccessible = true
                list = declaredField.get(thisClass) as? MutableList<*>
                    ?: continue
                break
            }
        }

        private fun Any?.getPackageName(): String? {
            return toString().split(",").getOrNull(1)
                ?.substringAfter("'")
                ?.substringBefore("'")
        }
    }

    @XposedHooker
    class GameBoosterSetDockTypeHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                // Placeholder for additional logic
            }
        }
    }
}
