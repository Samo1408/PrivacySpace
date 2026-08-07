package cn.geektang.privacyspace.ui.screen.setconnectedapps

import android.content.pm.ApplicationInfo
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.geektang.privacyspace.BuildConfig
import cn.geektang.privacyspace.R
import cn.geektang.privacyspace.bean.AppInfo
import cn.geektang.privacyspace.ui.widget.*
import cn.geektang.privacyspace.util.LocalNavHostController

@Composable
fun SetConnectedAppsScreen(viewModel: SetConnectedAppsViewModel = viewModel()) {
    val appInfoModel by viewModel.appInfoModel.collectAsState()
    val isLoading = appInfoModel.list.isEmpty()
    val connectedApps by viewModel.connectedApps.collectAsState()
    val actions = object : SetConnectedAppsActions {
        override fun addApp2ConnectedList(appInfo: AppInfo) { viewModel.addApp2ConnectedList(appInfo) }
        override fun removeApp2ConnectedList(appInfo: AppInfo) { viewModel.removeApp2ConnectedList(appInfo) }
        override fun addApp2BlindList(appInfo: AppInfo) { viewModel.addApp2BlindList(appInfo) }
        override fun removeApp2BlindList(appInfo: AppInfo) { viewModel.removeApp2BlindList(appInfo) }
    }
    SetConnectedAppsContent(appInfoModel = appInfoModel, connectedApps = connectedApps, isLoading = isLoading, actions = actions)
    OnLifecycleEvent { event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
            viewModel.tryUpdateConfig()
        }
    }
}

@Composable
fun SetConnectedAppsContent(appInfoModel: AppInfoModel, connectedApps: Set<String>, isLoading: Boolean, actions: SetConnectedAppsActions) {
    Column {
        val navController = LocalNavHostController.current
        TopBar(title = stringResource(R.string.set_connected_apps), onNavigationIconClick = { navController.popBackStack() })
        LoadingBox(modifier = Modifier.fillMaxSize(), showLoading = isLoading) {
            LazyColumn(content = {
                items(appInfoModel.list) { appInfo ->
                    val isChecked = connectedApps.contains(appInfo.packageName)
                    AppInfoColumnItem(appInfo, isChecked, onClick = {
                        if (!connectedApps.contains(appInfo.packageName)) actions.addApp2ConnectedList(appInfo)
                        else actions.removeApp2ConnectedList(appInfo)
                    })
                }
                item { Box(Modifier.navigationBarsPadding()) }
            })
        }
    }
}

interface SetConnectedAppsActions {
    fun addApp2ConnectedList(appInfo: AppInfo) {}
    fun removeApp2ConnectedList(appInfo: AppInfo) {}
    fun addApp2BlindList(appInfo: AppInfo) {}
    fun removeApp2BlindList(appInfo: AppInfo) {}
}

@Preview(showSystemUi = true)
@Composable
fun SetConnectedAppsScreenPreview() {
    val context = LocalContext.current
    val data = AppInfo(appIcon = ColorDrawable(), packageName = BuildConfig.APPLICATION_ID, appName = context.getString(R.string.app_name),
        sharedUserId = null, isXposedModule = true, isSystemApp = false, applicationInfo = ApplicationInfo())
    SetConnectedAppsContent(AppInfoModel(listOf(data, data, data)), setOf(BuildConfig.APPLICATION_ID), false, object : SetConnectedAppsActions {})
}
