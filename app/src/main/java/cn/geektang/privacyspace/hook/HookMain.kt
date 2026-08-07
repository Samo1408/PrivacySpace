package cn.geektang.privacyspace.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.FileObserver
import cn.geektang.privacyspace.bean.ConfigData
import cn.geektang.privacyspace.constant.ConfigConstant
import cn.geektang.privacyspace.hook.impl.*
import cn.geektang.privacyspace.util.*
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

class HookMain(base: XposedInterface, modulePath: String) : XposedModule(base, modulePath) {

    companion object {
        private lateinit var classLoader: ClassLoader
        private var packageName: String? = null
        private var fileObserver: FileObserver? = null
        private val configServer = ConfigServer()

        @Volatile
        var configData: ConfigData = ConfigData.EMPTY
            private set

        fun updateConfigData(data: ConfigData) {
            val sharedUserIdMap = data.sharedUserIdMap
            val whitelistTmp = data.whitelist.toMutableSet()
            val connectedAppsTpm = data.connectedApps.toMutableMap()
            if (!sharedUserIdMap.isNullOrEmpty()) {
                sharedUserIdMap.forEach {
                    if (whitelistTmp.contains(it.key)) {
                        whitelistTmp.add(it.value)
                    }
                }

                val connectedAppsNew = mutableMapOf<String, Set<String>>()
                connectedAppsTpm.forEach {
                    val newSet = it.value.toMutableSet()
                    it.value.forEach { packageName ->
                        val sharedUserId = sharedUserIdMap[packageName]
                        if (!sharedUserId.isNullOrEmpty()) {
                            newSet.add(sharedUserId)
                        }
                    }
                    connectedAppsNew[it.key] = newSet

                    val keySharedUserId = sharedUserIdMap[it.key]
                    if (!keySharedUserId.isNullOrEmpty()) {
                        connectedAppsNew[keySharedUserId] = newSet
                    }
                }
                connectedAppsTpm.putAll(connectedAppsNew)
            }

            this.configData = configData.copy(
                whitelist = whitelistTmp,
                connectedApps = connectedAppsTpm
            )
            XLog.enableLog = configData.enableDetailLog
        }
    }

    override fun onPackageLoaded(packageLoadedParam: XposedModuleInterface.PackageLoadedParam) {
        packageName = packageLoadedParam.packageName
        classLoader = packageLoadedParam.classLoader

        if (packageLoadedParam.packageName == ConfigConstant.ANDROID_FRAMEWORK) {
            loadConfigDataAndParse()
            configServer.start(classLoader = classLoader)
            when {
                Build.VERSION.SDK_INT >= 30 -> {
                    FrameworkHookerApi30Impl.start(classLoader)
                }
                Build.VERSION.SDK_INT >= 28 -> {
                    FrameworkHookerApi28Impl.start(classLoader)
                }
                else -> {
                    FrameworkHookerApi26Impl.start(classLoader)
                }
            }
        } else if ("com.android.settings" == packageLoadedParam.packageName) {
            SettingsAppHookImpl.start(classLoader)
            hook(Application::class.java.getDeclaredMethod("onCreate"))
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val configClient = ConfigClient(chain.args[0] as Context)
                        MainScope().launch {
                            val config = configClient.queryConfig()
                            XLog.i("config = $config")
                            if (null != config) {
                                updateConfigData(config)
                            }
                        }
                        return chain.proceed()
                    }
                })
        } else if (packageLoadedParam.appInfo != null && AppHelper.isSystemApp(packageLoadedParam.appInfo)) {
            XLog.i("Hook class fdfasdfs start.")
            loadConfigDataAndParse()
            startWatchingConfigFiles()
            SpecialAppsHookerImpl.start(classLoader)
        }
    }

    override fun onSystemServerLoaded(systemServerLoadedParam: XposedModuleInterface.SystemServerLoadedParam) {
        super.onSystemServerLoaded(systemServerLoadedParam)
        packageName = "android"
        classLoader = systemServerLoadedParam.classLoader
        loadConfigDataAndParse()
        configServer.start(classLoader = classLoader)
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                FrameworkHookerApi30Impl.start(classLoader)
            }
            Build.VERSION.SDK_INT >= 28 -> {
                FrameworkHookerApi28Impl.start(classLoader)
            }
            else -> {
                FrameworkHookerApi26Impl.start(classLoader)
            }
        }
    }

    private fun startWatchingConfigFiles() {
        if (null == fileObserver) {
            val file = File(ConfigConstant.CONFIG_FILE_FOLDER)
            file.mkdirs()
            fileObserver =
                object : FileObserver(ConfigConstant.CONFIG_FILE_FOLDER) {
                    override fun onEvent(event: Int, path: String?) {
                        if (event == CLOSE_WRITE && path == ConfigConstant.CONFIG_FILE_JSON) {
                            loadConfigDataAndParse()
                            XLog.i("$packageName reload config.json")
                        }
                    }
                }
            fileObserver?.startWatching()
        }
    }

    private fun loadConfigDataAndParse() {
        val configDataNew =
            ConfigHelper.loadConfigWithSystemApp(packageName ?: "") ?: ConfigData.EMPTY
        if (configDataNew != configData) {
            configData = configDataNew
            updateConfigData(configDataNew)
        }
    }
}
