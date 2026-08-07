package cn.geektang.privacyspace.hook.impl

import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.tryLoadClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object FrameworkHookerApi30Impl : XC_MethodHook(), Hooker {
    private lateinit var classLoader: ClassLoader

    override fun start(classLoader: ClassLoader) {
        this.classLoader = classLoader
        val appsFilterClass: Class<*>
        val settingBaseClass: Class<*>
        val packageSettingClass: Class<*>
        try {
            appsFilterClass = classLoader.tryLoadClass("com.android.server.pm.AppsFilter")
            settingBaseClass = classLoader.tryLoadClass("com.android.server.pm.SettingBase")
            packageSettingClass = classLoader.tryLoadClass("com.android.server.pm.PackageSetting")
        } catch (e: ClassNotFoundException) { XLog.e(e, "FrameworkHookerApi30Impl start failed."); return }
        XposedHelpers.findAndHookMethod(appsFilterClass, "shouldFilterApplication",
            Int::class.javaPrimitiveType, settingBaseClass, packageSettingClass, Int::class.javaPrimitiveType, this)
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        if (param.result == true) return
        val targetPackageName = param.args[2]?.packageName ?: return
        val callingPackageName = param.args[1]?.packageName ?: return
        val userId = param.args[3] as Int
        if (HookChecker.shouldIntercept(classLoader = classLoader, targetPackageName = targetPackageName,
                callingPackageName = callingPackageName, userId = userId)) param.result = true
    }
}
