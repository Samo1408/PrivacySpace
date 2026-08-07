package cn.geektang.privacyspace.ui.screen.setwhitelist

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
fun SetWhitelistScreen(viewModel: SetWhitelistViewModel = viewModel()) {
    val appInfoModel by viewModel.appInfoModel.collectAsState()
    val whitelist by viewModel.whitelist.collectAsState()
    val isLoading = appInfoModel.list.isEmpty()
    val actions = object : SetWhitelistActions {
        override fun addApp2Whitelist(appInfo: AppInfo) { viewModel.addApp2Whitelist(appInfo) }
        override fun removeApp2Whitelist(appInfo: AppInfo) { viewModel.removeApp2Whitelist(appInfo) }
    }
    SetWhitelistContent(appInfoModel = appInfoModel, whitelist = whitelist, isLoading = isLoading, actions = actions)
    OnLifecycleEvent { event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) viewModel.tryUpdateConfig()
    }
}

@Composable
fun SetWhitelistContent(appInfoModel: AppInfoModel, whitelist: Set<String>, isLoading: Boolean, actions: SetWhitelistActions) {
    Column {
        val navController = LocalNavHostController.current
        TopBar(title = stringResource(R.string.set_whitelist), onNavigationIconClick = { navController.popBackStack() })
        LoadingBox(modifier = Modifier.fillMaxSize(), showLoading = isLoading) {
            LazyColumn(content = {
                items(appInfoModel.list) { appInfo ->
                    val isChecked = whitelist.contains(appInfo.packageName)
                    AppInfoColumnItem(appInfo, isChecked, onClick = {
                        if (!whitelist.contains(appInfo.packageName)) actions.addApp2Whitelist(appInfo)
                        else actions.removeApp2Whitelist(appInfo)
                    })
                }
                item { Box(Modifier.navigationBarsPadding()) }
            })
        }
    }
}

interface SetWhitelistActions {
    fun addApp2Whitelist(appInfo: AppInfo) {}
    fun removeApp2Whitelist(appInfo: AppInfo) {}
}

@Preview(showSystemUi = true)
@Composable
fun SetWhitelistScreenPreview() {
    val context = LocalContext.current
    val data = AppInfo(appIcon = ColorDrawable(), packageName = BuildConfig.APPLICATION_ID, appName = context.getString(R.string.app_name),
        sharedUserId = null, isXposedModule = true, isSystemApp = false, applicationInfo = ApplicationInfo())
    SetWhitelistContent(AppInfoModel(listOf(data, data, data)), setOf(BuildConfig.APPLICATION_ID), false, object : SetWhitelistActions {})
}
