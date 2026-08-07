package cn.geektang.privacyspace.hook.impl

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ServiceManager
import android.util.ArrayMap
import android.util.SparseArray
import androidx.core.util.forEach
import cn.geektang.privacyspace.constant.ConfigConstant
import cn.geektang.privacyspace.hook.HookMain
import cn.geektang.privacyspace.util.HookUtil
import cn.geektang.privacyspace.util.XLog

object HookChecker {
    @Volatile
    private var greenChannel = false
    private var defaultBlindWhitelist: Set<String> = emptySet()

    @JvmStatic
    fun shouldIntercept(classLoader: ClassLoader, userId: Int, targetPackageName: String, callingPackageName: String): Boolean {
        if (greenChannel) return false

        if (defaultBlindWhitelist.isEmpty()) {
            greenChannel = true
            val sharedUserIdMap = getSharedUserIdMap(classLoader)
            if (null != sharedUserIdMap) {
                val blindWhitelist = ConfigConstant.defaultBlindWhitelist.toMutableSet()
                for (white in ConfigConstant.defaultBlindWhitelist) blindWhitelist.addAll(sharedUserIdMap[white] ?: emptyList())
                this@HookChecker.defaultBlindWhitelist = blindWhitelist
            }
        }
        greenChannel = false

        if (callingPackageName == targetPackageName) return false

        var result = false
        val configData = HookMain.configData
        val shouldFilterAppList = configData.hiddenAppList
        val userWhitelist = configData.whitelist
        val connectedAppsInfoMap = configData.connectedApps
        val multiUserConfig = configData.multiUserConfig ?: emptyMap()
        val blindApps = configData.blind ?: emptySet()
        XLog.enableLog = configData.enableDetailLog

        if (defaultBlindWhitelist.isNotEmpty()
            && !defaultBlindWhitelist.contains(targetPackageName)
            && blindApps.contains(callingPackageName)
            && connectedAppsInfoMap[callingPackageName]?.contains(targetPackageName) != true
            && connectedAppsInfoMap[targetPackageName]?.contains(callingPackageName) != true
        ) { XLog.d("$callingPackageName was prevented from reading $targetPackageName."); return true }

        if (!ConfigConstant.defaultWhitelist.contains(callingPackageName) && shouldFilterAppList.contains(targetPackageName)) {
            val appMultiUserConfig = multiUserConfig[targetPackageName]
            if (!userWhitelist.contains(callingPackageName)
                && connectedAppsInfoMap[callingPackageName]?.contains(targetPackageName) != true
                && connectedAppsInfoMap[targetPackageName]?.contains(callingPackageName) != true
                && (appMultiUserConfig.isNullOrEmpty() || appMultiUserConfig.contains(userId))
            ) { result = true; XLog.d("$callingPackageName was prevented from reading $targetPackageName.") }
        }
        return result
    }

    private fun getSharedUserIdMap(classLoader: ClassLoader): Map<String, List<String>>? {
        val pms = ServiceManager.getService("package")
        val pmsClass = HookUtil.loadPms(classLoader)
        if (pms?.javaClass == pmsClass) return if (Build.VERSION.SDK_INT >= 29) getSharedUidMapAfterQ(pms) else getSharedUidMapCompat(pms)
        return null
    }

    private fun getSharedUidMapAfterQ(pms: Any): Map<String, List<String>>? = try {
        val pmsClass = pms.javaClass
        val getAppsWithSharedUserMethod = pmsClass.getDeclaredMethod("getAppsWithSharedUserIdsLocked").apply { isAccessible = true }
        val getPackagesForUidMethod = pmsClass.getDeclaredMethod("getPackagesForUid", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val sharedUserIdMap = ArrayMap<String, List<String>>()
        (getAppsWithSharedUserMethod.invoke(pms) as SparseArray<*>).forEach { key, value ->
            sharedUserIdMap[value.toString()] = (getPackagesForUidMethod.invoke(pms, key) as Array<*>).map { it.toString() }
        }
        sharedUserIdMap
    } catch (_: Throwable) { getSharedUidMapCompat(pms) }

    private fun getSharedUidMapCompat(pms: Any): Map<String, List<String>>? = try {
        val pmsClass = pms.javaClass
        val getInstalledPackagesMethod = pmsClass.getDeclaredMethod("getInstalledPackages", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val resultSlice = getInstalledPackagesMethod.invoke(pms, PackageManager.MATCH_UNINSTALLED_PACKAGES, 0)
        val resultList = resultSlice.javaClass.getDeclaredMethod("getList").apply { isAccessible = true }.invoke(resultSlice) as? List<*> ?: return null
        val sharedUserIdMap = ArrayMap<String, MutableList<String>>()
        for (packageInfo in resultList) {
            if (packageInfo !is PackageInfo || packageInfo.sharedUserId.isNullOrEmpty()) continue
            sharedUserIdMap.getOrPut(packageInfo.sharedUserId!!) { mutableListOf() }.add(packageInfo.packageName)
        }
        sharedUserIdMap
    } catch (_: Throwable) { null }
}
