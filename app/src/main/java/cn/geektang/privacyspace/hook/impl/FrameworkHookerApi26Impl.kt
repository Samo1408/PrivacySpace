package cn.geektang.privacyspace.hook.impl

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Binder
import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.ConfigHelper.getPackageName
import cn.geektang.privacyspace.util.HookUtil
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.tryLoadClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Method

object FrameworkHookerApi26Impl : Hooker {
    private lateinit var pmsClass: Class<*>
    private lateinit var packageSettingClass: Class<*>
    private lateinit var getPackageNameForUidMethod: Method
    private lateinit var classLoader: ClassLoader

    override fun start(classLoader: ClassLoader) {
        this.classLoader = classLoader
        try {
            pmsClass = HookUtil.loadPms(classLoader) ?: throw PackageManager.NameNotFoundException()
            packageSettingClass =
                classLoader.tryLoadClass("com.android.server.pm.PackageSetting")
            getPackageNameForUidMethod = pmsClass.getDeclaredMethod(
                "getNameForUid",
                Int::class.javaPrimitiveType
            )
            getPackageNameForUidMethod.isAccessible = true
        } catch (e: Throwable) {
            XLog.e(e, "pmsClass load failed.")
            return
        }

        pmsClass.declaredMethods.forEach { method ->
            when (method.name) {
                "filterSharedLibPackageLPr" -> {
                    if (method.parameterCount == 4) {
                        XposedModule.hook(method, FilterSharedLibHooker::class.java)
                    }
                }
                "applyPostResolutionFilter" -> {
                    XposedModule.hook(method, ApplyPostResolutionFilterHooker::class.java)
                }
                else -> {}
            }
        }
    }

    @XposedHooker
    class FilterSharedLibHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                if (callback.result == true) {
                    return
                }
                val packageSetting = callback.args.first()
                val targetPackageName = packageSetting?.packageName ?: return
                val userId = callback.args[2] as? Int ?: return
                val callingPackageName =
                    getPackageNameForUidMethod.invoke(callback.thisObject, callback.args[1])
                        ?.toString()?.split(":")?.first() ?: return

                val shouldIntercept = HookChecker.shouldIntercept(
                    classLoader,
                    userId,
                    targetPackageName,
                    callingPackageName
                )
                if (shouldIntercept) {
                    callback.result = true
                }
            }
        }
    }

    @XposedHooker
    class ApplyPostResolutionFilterHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val resultList = callback.result as? MutableList<*> ?: return
                val uid = Binder.getCallingUid()
                val userId = uid / 100000
                val callingPackageName =
                    getPackageNameForUidMethod.invoke(callback.thisObject, uid)
                        ?.toString()?.split(":")?.first() ?: return
                val waitRemoveList = mutableListOf<ResolveInfo>()
                for (resolveInfo in resultList) {
                    val targetPackageName = (resolveInfo as? ResolveInfo)?.getPackageName() ?: continue
                    val shouldIntercept = HookChecker.shouldIntercept(
                        classLoader,
                        userId,
                        targetPackageName,
                        callingPackageName
                    )
                    if (shouldIntercept) {
                        waitRemoveList.add(resolveInfo)
                    }
                }
                for (resolveInfo in waitRemoveList) {
                    resultList.remove(resolveInfo)
                }
                if (waitRemoveList.isNotEmpty()) {
                    callback.result = resultList
                }
            }
        }
    }
}
