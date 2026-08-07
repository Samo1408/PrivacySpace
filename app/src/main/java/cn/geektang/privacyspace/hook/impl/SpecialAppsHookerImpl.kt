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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor

object SpecialAppsHookerImpl : XC_MethodHook(), Hooker {
    override fun start(classLoader: ClassLoader) {
        val packageManagerClass = try {
            classLoader.tryLoadClass("android.app.ApplicationPackageManager")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "SpecialAppsHookerImpl start failed.")
            return
        }
        val hookMethodSet = setOf("getInstalledPackages", "getInstalledApplications", "getInstalledModules")
        packageManagerClass.declaredMethods.forEach {
            if (hookMethodSet.contains(it.name)) XposedBridge.hookMethod(it, this)
        }
        XposedHelpers.findAndHookMethod(BatteryStatsHelper::class.java, "getUsageList", this)
        XposedHelpers.findAndHookMethod(BatteryStatsHelper::class.java, "getMobilemsppList", this)
        XposedHelpers.findAndHookMethod(ActivityManager::class.java, "getRunningAppProcesses", this)

        val miuiRvClass = classLoader.loadClassSafe("miuix.recyclerview.widget.RecyclerView")
        if (miuiRvClass != null) {
            var unhook: XC_MethodHook.Unhook? = null
            unhook = XposedHelpers.findAndHookConstructor(miuiRvClass, Context::class.java,
                GameBoosterHooker { unhook?.unhook(); XLog.d("unhook ${miuiRvClass.name} Constructor ${if (unhook != null) "succeed" else "failed"}!") }
            )
        }

        val dockAppEditActivityClass = classLoader.loadClassSafe("com.miui.dock.edit.DockAppEditActivity")
        if (null != dockAppEditActivityClass) {
            for (method in dockAppEditActivityClass.declaredMethods) {
                if (method.parameterCount == 1 && method.parameterTypes.first() == PackageInfo::class.java) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val shouldFilterAppList = HookMain.configData.hiddenAppList
                            val packageInfo = param.args.first() as PackageInfo
                            if (shouldFilterAppList.contains(packageInfo.packageName)) param.result = Unit
                        }
                    })
                }
            }
        }
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val shouldFilterAppList = HookMain.configData.hiddenAppList
        when (param.method.name) {
            "getInstalledPackages" -> param.result = (param.result as? List<*>?)?.filter {
                val packageName = (it as? PackageInfo)?.packageName ?: return@filter true
                !shouldFilterAppList.contains(packageName)
            }
            "getInstalledApplications" -> param.result = (param.result as? List<*>?)?.filter {
                val packageName = (it as? ApplicationInfo)?.packageName ?: return@filter true
                !shouldFilterAppList.contains(packageName)
            }
            "getInstalledModules" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    param.result = (param.result as? List<*>?)?.filter {
                        val packageName = (it as? ModuleInfo)?.packageName ?: return@filter true
                        !shouldFilterAppList.contains(packageName)
                    }
                }
            }
            "getUsageList", "getMobilemsppList" -> {
                val result = param.result as? MutableList<*> ?: return
                val iterator = result.iterator()
                while (iterator.hasNext()) {
                    val batterySipper = (iterator.next() as? BatterySipper) ?: continue
                    val packages = batterySipper.packages
                    if (packages.isNullOrEmpty()) continue
                    for (packageName in packages) {
                        if (shouldFilterAppList.contains(packageName)) { iterator.remove(); break }
                    }
                }
            }
            "getRunningAppProcesses" -> {
                val result = param.result as? List<*> ?: return
                param.result = result.filter {
                    it as ActivityManager.RunningAppProcessInfo
                    var shouldFilter = false
                    it.pkgList.forEach { pkg -> if (shouldFilterAppList.contains(pkg)) shouldFilter = true }
                    !shouldFilter
                }
            }
        }
    }

    private inner class GameBoosterHooker(private val unhookCallback: () -> Unit) : XC_MethodHook() {
        private var list: MutableList<*>? = null

        override fun afterHookedMethod(param: MethodHookParam) {
            val thisClass = param.thisObject.javaClass
            if (param.method is Constructor<*>) {
                if (!thisClass.name.startsWith("com.miui.gamebooster.windowmanager.")) return
                XLog.d("hook class $thisClass")
                for (method in thisClass.declaredMethods) {
                    if (method.name != "setDockType" && method.parameterCount == 1
                        && method.parameterTypes.first() == Int::class.javaPrimitiveType)
                        XposedBridge.hookMethod(method, this)
                }
                unhookCallback()
            } else {
                queryAndGetList(thisClass, param)
                val listObj = list ?: return
                val iterator = listObj.iterator()
                val sfList = HookMain.configData.hiddenAppList
                while (iterator.hasNext()) {
                    val appInfo = iterator.next() ?: continue
                    if (shouldFilter(appInfo.javaClass, appInfo, sfList)) iterator.remove()
                }
            }
        }

        private fun shouldFilter(appInfoClass: Class<Any>, appInfo: Any, sfList: Set<String>): Boolean {
            for (declaredField in appInfoClass.declaredFields) {
                declaredField.isAccessible = true
                val packageName = declaredField.get(appInfo)?.getPackageName() ?: continue
                if (packageName.isNotBlank() && sfList.contains(packageName)) { XLog.d("filter class $packageName"); return true }
            }
            return false
        }

        private fun queryAndGetList(thisClass: Class<Any>, param: MethodHookParam) {
            for (declaredField in thisClass.declaredFields) {
                declaredField.isAccessible = true
                list = declaredField.get(param.thisObject) as? MutableList<*> ?: continue
                break
            }
        }

        private fun Any?.getPackageName(): String? = toString().split(",").getOrNull(1)?.substringAfter("'")?.substringBefore("'")
    }
}
