package cn.geektang.privacyspace.hook.impl

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.UserHandle
import cn.geektang.privacyspace.hook.Hooker
import cn.geektang.privacyspace.util.ConfigHelper.getPackageName
import cn.geektang.privacyspace.util.HookUtil
import cn.geektang.privacyspace.util.XLog
import cn.geektang.privacyspace.util.tryLoadClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Field
import java.lang.reflect.Method

object FrameworkHookerApi28Impl : Hooker {
    private lateinit var pmsClass: Class<*>
    private lateinit var settingsClass: Class<*>
    private lateinit var mSettingsField: Field
    private lateinit var getAppIdMethod: Method
    private lateinit var getSettingLPrMethod: Method
    private lateinit var classLoader: ClassLoader

    override fun start(classLoader: ClassLoader) {
        this.classLoader = classLoader
        try {
            pmsClass = HookUtil.loadPms(classLoader) ?: throw PackageManager.NameNotFoundException()
            mSettingsField = pmsClass.getDeclaredField("mSettings")
            mSettingsField.isAccessible = true

            settingsClass = classLoader.tryLoadClass("com.android.server.pm.Settings")
            getAppIdMethod =
                UserHandle::class.java.getDeclaredMethod("getAppId", Int::class.javaPrimitiveType)
            getAppIdMethod.isAccessible = true

            for (method in settingsClass.declaredMethods) {
                if ((method.name == "getSettingLPr" || method.name == "getUserIdLPr")
                    && method.parameterCount == 1
                    && method.parameterTypes.first() == Int::class.javaPrimitiveType
                ) {
                    getSettingLPrMethod = method
                    method.isAccessible = true
                    break
                }
            }
        } catch (e: Throwable) {
            XLog.e(e, "pms load failed.")
            return
        }
        pmsClass.declaredMethods.forEach { method ->
            when (method.name) {
                "filterAppAccessLPr" -> {
                    if (method.parameterCount == 5) {
                        XposedModule.hook(method, FilterAppAccessHooker::class.java)
                    }
                }
                "applyPostResolutionFilter" -> {
                    XposedModule.hook(method, ApplyPostResolutionFilterHooker::class.java)
                }
                else -> {}
            }
        }
    }

    private fun getPackageName(pms: Any, uid: Int): String? {
        val callingAppId = getAppIdMethod.invoke(null, uid)
        val mSettings = mSettingsField.get(pms)
        return getSettingLPrMethod.invoke(mSettings, callingAppId)?.packageName
    }

    @XposedHooker
    class FilterAppAccessHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                if (callback.result == true) {
                    return
                }
                val packageSetting = callback.args.first()
                val targetPackageName = packageSetting?.packageName ?: return
                val callingUid = callback.args[1] as Int
                val userId = callback.args[4] as Int
                val callingPackageName = getPackageName(callback.thisObject, callingUid) ?: return

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
                val callingUid = callback.args[3] as? Int ?: return
                val userId = callback.args[5] as? Int ?: return
                val callingPackageName = getPackageName(callback.thisObject, callingUid) ?: return
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
