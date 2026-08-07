package cn.geektang.privacyspace.hook.impl

import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.tryLoadClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Executable

object FrameworkHookerApi30Impl : Hooker {
    private lateinit var classLoader: ClassLoader

    override fun start(classLoader: ClassLoader) {
        this.classLoader = classLoader
        val appsFilterClass: Class<*>
        val settingBaseClass: Class<*>
        val packageSettingClass: Class<*>
        try {
            appsFilterClass = classLoader.tryLoadClass("com.android.server.pm.AppsFilter")
            settingBaseClass = classLoader.tryLoadClass("com.android.server.pm.SettingBase")
            packageSettingClass =
                classLoader.tryLoadClass("com.android.server.pm.PackageSetting")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "FrameworkHookerApi30Impl start failed.")
            return
        }

        // Find the class that contains the hook method (the module itself or a utility)
        val hookerClass = classLoader.loadClass(
            "cn.geektang.privacyspace.hook.impl.FrameworkHookerApi30Impl"
        )

        // Use reflection to find the actual executable to hook
        try {
            val method = appsFilterClass.getDeclaredMethod(
                "shouldFilterApplication",
                Int::class.javaPrimitiveType,
                settingBaseClass,
                packageSettingClass,
                Int::class.javaPrimitiveType
            )
            XposedModule.hook(method, ShouldFilterHooker::class.java)
        } catch (e: Exception) {
            XLog.e(e, "Failed to hook shouldFilterApplication")
        }
    }

    @XposedHooker
    class ShouldFilterHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                if (callback.result == true) {
                    return
                }
                val targetPackageName =
                    (callback.args[2] as? Any)?.let { obj ->
                        obj.javaClass.getDeclaredField("packageName").apply { isAccessible = true }.get(obj) as? String
                    } ?: return
                val callingPackageName =
                    (callback.args[1] as? Any)?.let { obj ->
                        obj.javaClass.getDeclaredField("packageName").apply { isAccessible = true }.get(obj) as? String
                    } ?: return
                val userId = callback.args[3] as Int
                val classLoader = callback.args[0]?.javaClass?.classLoader ?: return

                val shouldIntercept = HookChecker.shouldIntercept(
                    classLoader = classLoader,
                    targetPackageName = targetPackageName,
                    callingPackageName = callingPackageName,
                    userId = userId
                )
                if (shouldIntercept) {
                    callback.result = true
                }
            }
        }
    }
}
