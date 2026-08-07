package cn.geektang.privacyspace.util

import android.app.ActivityThread
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Binder
import android.os.ServiceManager
import android.os.SystemProperties
import cn.geektang.privacyspace.BuildConfig
import cn.geektang.privacyspace.bean.SystemUserInfo
import cn.geektang.privacyspace.constant.ConfigConstant
import cn.geektang.privacyspace.hook.HookMain
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.io.File
import java.lang.reflect.Method

class ConfigServer {
    companion object {
        const val QUERY_SERVER_VERSION = "serverVersion"
        const val MIGRATE_OLD_CONFIG_FILE = "migrateOldConfigFile"
        const val QUERY_CONFIG = "queryConfig"
        const val UPDATE_CONFIG = "updateConfig:"
        const val REBOOT_THE_SYSTEM = "rebootTheSystem"
        const val GET_USERS = "getUsers"
        const val FORCE_STOP = "forceStop:"

        const val EXEC_SUCCEED = "1"
        const val EXEC_FAILED = "0"
    }

    private lateinit var classLoader: ClassLoader
    private var pmsClass: Class<*>? = null
    private var userInfoListCache: Collection<*>? = null

    @Suppress("DEPRECATION")
    fun start(classLoader: ClassLoader) {
        pmsClass = HookUtil.loadPms(classLoader)
        this.classLoader = classLoader
        if (pmsClass == null) {
            XLog.e("ConfigServer start failed.")
            return
        }

        // Hook getInstallerPackageName using libxposed API
        try {
            val method = pmsClass!!.getDeclaredMethod(
                "getInstallerPackageName",
                String::class.java
            )
            XposedModule.hook(method, GetInstallerPackageNameHooker::class.java)
        } catch (e: Exception) {
            XLog.e(e, "Hook getInstallerPackageName failed.")
        }

        val userManagerClass = try {
            classLoader.tryLoadClass("com.android.server.pm.UserManagerService")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "Find UserManagerService failed.")
            return
        }
        userManagerClass.declaredMethods.filter { method ->
            method.checkIsGetUsersMethod()
        }.forEach { method ->
            XposedModule.hook(method, GetUsersHooker::class.java)
        }
    }

    @XposedHooker
    class GetInstallerPackageNameHooker : XposedInterface.Hooker {
        companion object {
            @BeforeInvocation
            @JvmStatic
            @Suppress("DEPRECATION")
            fun beforeHookedMethod(callback: XposedInterface.HookerCallback) {
                val param = callback
                val callingUid = Binder.getCallingUid()
                val server = ConfigServer()
                if (callingUid != server.getPackageUid(BuildConfig.APPLICATION_ID) &&
                    callingUid != server.getPackageUid("com.android.settings")
                ) {
                    return
                }
                val firstArg = callback.args.firstOrNull()?.toString() ?: return
                when {
                    firstArg == QUERY_SERVER_VERSION -> {
                        callback.result = BuildConfig.VERSION_CODE.toString()
                    }
                    firstArg == MIGRATE_OLD_CONFIG_FILE -> {
                        server.tryMigrateOldConfig()
                        callback.result = ""
                    }
                    firstArg == QUERY_CONFIG -> {
                        callback.result = server.queryConfigInternal()
                    }
                    firstArg == REBOOT_THE_SYSTEM -> {
                        SystemProperties.set("sys.powerctl", "reboot")
                        callback.result = ""
                    }
                    firstArg == GET_USERS -> {
                        val users = userInfoListCache
                        val systemUsers = mutableListOf<SystemUserInfo>()
                        users?.forEach { userInfo ->
                            if (userInfo !is UserInfo) return@forEach
                            val systemUserInfo = SystemUserInfo(
                                id = userInfo.id,
                                name = userInfo.name
                            )
                            systemUsers.add(systemUserInfo)
                        }
                        callback.result = JsonHelper.systemUserInfoListAdapter().toJson(systemUsers)
                    }
                    firstArg.startsWith(UPDATE_CONFIG) -> {
                        val arg = firstArg.substring(UPDATE_CONFIG.length)
                        server.updateConfigInternal(arg)
                        callback.result = ""
                    }
                    firstArg.startsWith(FORCE_STOP) -> {
                        val arg = firstArg.substring(FORCE_STOP.length)
                        callback.result = server.forceStopPackageInternal(arg)
                    }
                }
            }
        }
    }

    @XposedHooker
    class GetUsersHooker : XposedInterface.Hooker {
        companion object {
            @AfterInvocation
            @JvmStatic
            fun afterHookedMethod(callback: XposedInterface.HookerCallback) {
                val userInfoListTmp = callback.result as? Collection<*>? ?: return
                // Update the cache
                callback.result?.let {
                    // Store reference via companion
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun forceStopPackageInternal(packageName: String): String {
        XLog.d("forceStopPackage = $packageName")
        val callingUid = Binder.getCallingUid()
        val ams = ServiceManager.getService("activity")
        var checkPermissionUnhook: XposedInterface.Unhook? = null
        try {
            checkPermissionUnhook = XposedModule.hook(
                ams.javaClass.getDeclaredMethod(
                    "checkPermission",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ),
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val uid = chain.args[2] as Int
                        if (callingUid == uid) {
                            return PackageManager.PERMISSION_GRANTED
                        }
                        return chain.proceed()
                    }
                }
            )
        } catch (_: Exception) {}

        val isExecSucceed = try {
            val method = ams.javaClass.getDeclaredMethod(
                "forceStopPackage",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(ams, packageName, 0)
            EXEC_SUCCEED
        } catch (e: Throwable) {
            XLog.e(e, "forceStopPackage $packageName failed.")
            EXEC_FAILED
        } finally {
            checkPermissionUnhook?.unhook()
        }
        return isExecSucceed
    }

    private fun queryConfigInternal(): String {
        val configFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        return try {
            configFile.readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun updateConfigInternal(configJson: String) {
        val configFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        configFile.parentFile?.mkdirs()
        try {
            val configData = JsonHelper.configAdapter().fromJson(configJson)
            if (null != configData) {
                HookMain.updateConfigData(configData)
                configFile.writeText(configJson)
            }
        } catch (e: Exception) {
            XLog.e(e, "Update config error.")
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageUid(packageName: String): Int {
        return try {
            ActivityThread.getPackageManager()
                .getPackageUid(packageName, 0, 0)
        } catch (e: Throwable) {
            XLog.d("ConfigServer (${Binder.getCallingUid()}).getClientUid failed.")
            -1
        }
    }

    private fun tryMigrateOldConfig() {
        val originalFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER_ORIGINAL}${ConfigConstant.CONFIG_FILE_JSON}")
        val newConfigFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        if (!newConfigFile.exists()) {
            newConfigFile.parentFile?.mkdirs()
            originalFile.copyTo(newConfigFile)
        }
    }

    private fun Method.checkIsGetUsersMethod(): Boolean {
        if (name != "getUsers") {
            return false
        }
        var isGetUsersMethod = false
        if (parameterCount == 1 && parameterTypes.first() == Boolean::class.javaPrimitiveType) {
            isGetUsersMethod = true
        } else if (parameterCount == 3) {
            isGetUsersMethod = true
            for (parameterType in parameterTypes) {
                if (parameterType != Boolean::class.javaPrimitiveType) {
                    isGetUsersMethod = false
                    break
                }
            }
        }
        return isGetUsersMethod
    }
}
