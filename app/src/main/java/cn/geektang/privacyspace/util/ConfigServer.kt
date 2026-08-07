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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method

class ConfigServer : XC_MethodHook() {
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

    fun start(classLoader: ClassLoader) {
        pmsClass = HookUtil.loadPms(classLoader)
        this.classLoader = classLoader
        if (pmsClass == null) { XLog.e("ConfigServer start failed."); return }
        XposedHelpers.findAndHookMethod(pmsClass, "getInstallerPackageName", String::class.java, this)
        val userManagerClass = try {
            classLoader.tryLoadClass("com.android.server.pm.UserManagerService")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "Find UserManagerService failed."); return
        }
        userManagerClass.declaredMethods.filter { it.checkIsGetUsersMethod() }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    userInfoListCache = param.result as? Collection<*>?
                }
            })
        }
    }

    override fun beforeHookedMethod(param: MethodHookParam) {
        if (param.method.name == "getInstallerPackageName") hookGetInstallerPackageName(param)
    }

    private fun hookGetInstallerPackageName(param: MethodHookParam) {
        val callingUid = Binder.getCallingUid()
        if (callingUid != getPackageUid(BuildConfig.APPLICATION_ID) && callingUid != getPackageUid("com.android.settings")) return
        val firstArg = param.args.firstOrNull()?.toString() ?: return
        when {
            firstArg == QUERY_SERVER_VERSION -> param.result = BuildConfig.VERSION_CODE.toString()
            firstArg == MIGRATE_OLD_CONFIG_FILE -> { tryMigrateOldConfig(); param.result = "" }
            firstArg == QUERY_CONFIG -> param.result = queryConfig()
            firstArg == REBOOT_THE_SYSTEM -> { SystemProperties.set("sys.powerctl", "reboot"); param.result = "" }
            firstArg == GET_USERS -> {
                val users = userInfoListCache
                val systemUsers = mutableListOf<SystemUserInfo>()
                users?.forEach { if (it is UserInfo) systemUsers.add(SystemUserInfo(id = it.id, name = it.name)) }
                param.result = JsonHelper.systemUserInfoListAdapter().toJson(systemUsers)
            }
            firstArg.startsWith(UPDATE_CONFIG) -> { updateConfig(firstArg.substring(UPDATE_CONFIG.length)); param.result = "" }
            firstArg.startsWith(FORCE_STOP) -> param.result = forceStopPackage(firstArg.substring(FORCE_STOP.length))
        }
    }

    private fun forceStopPackage(packageName: String): String {
        XLog.d("forceStopPackage = $packageName")
        val callingUid = Binder.getCallingUid()
        val ams = ServiceManager.getService("activity")
        val checkPermissionUnhook = XposedHelpers.findAndHookMethod(ams.javaClass, "checkPermission",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (callingUid == param.args[2]) param.result = PackageManager.PERMISSION_GRANTED
                }
            })
        return try {
            val method = ams.javaClass.getDeclaredMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true; method.invoke(ams, packageName, 0); EXEC_SUCCEED
        } catch (e: Throwable) { XLog.e(e, "forceStopPackage $packageName failed."); EXEC_FAILED
        } finally { checkPermissionUnhook.unhook() }
    }

    private fun queryConfig() = try { File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}").readText() } catch (_: Exception) { "" }

    private fun updateConfig(configJson: String) {
        val configFile = File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        configFile.parentFile?.mkdirs()
        try {
            val data = JsonHelper.configAdapter().fromJson(configJson)
            if (null != data) { HookMain.updateConfigData(data); configFile.writeText(configJson) }
        } catch (e: Exception) { XLog.e(e, "Update config error.") }
    }

    private fun getPackageUid(packageName: String) = try {
        ActivityThread.getPackageManager().getPackageUid(packageName, 0, 0)
    } catch (_: Throwable) { XLog.d("ConfigServer (${Binder.getCallingUid()}).getClientUid failed."); -1 }

    private fun tryMigrateOldConfig() {
        val newFile = File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        if (!newFile.exists()) {
            newFile.parentFile?.mkdirs()
            File("${ConfigConstant.CONFIG_FILE_FOLDER_ORIGINAL}${ConfigConstant.CONFIG_FILE_JSON}").copyTo(newFile)
        }
    }

    private fun Method.checkIsGetUsersMethod(): Boolean {
        if (name != "getUsers") return false
        if (parameterCount == 1 && parameterTypes.first() == Boolean::class.javaPrimitiveType) return true
        if (parameterCount == 3) return parameterTypes.all { it == Boolean::class.javaPrimitiveType }
        return false
    }
}
