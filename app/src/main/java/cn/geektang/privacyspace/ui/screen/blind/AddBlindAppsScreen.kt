package cn.geektang.privacyspace.ui.screen.blind

import android.content.pm.ApplicationInfo
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.*
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
fun AddBlindAppsScreen(viewModel: AddBlindAppsViewModel = viewModel()) {
    val appInfoList by viewModel.appInfoListFlow.collectAsState()
    val blindApps by viewModel.blindAppsFlow.collectAsState()
    val isLoading = appInfoList.isEmpty()
    AddBlindAppsContent(appInfoList = appInfoList, blindApps = blindApps, isLoading = isLoading,
        onAppCheckedChange = { appInfo, checked -> viewModel.onAppCheckedChange(appInfo, checked) })
    OnLifecycleEvent { event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY)
            viewModel.tryUpdateConfig()
    }
}

@Composable
fun AddBlindAppsContent(appInfoList: List<AppInfo>, blindApps: Set<String>, isLoading: Boolean,
    onAppCheckedChange: (AppInfo, Boolean) -> Unit
) {
    Column {
        val navController = LocalNavHostController.current
        TopBar(title = stringResource(R.string.set_blind_apps),
            onNavigationIconClick = { navController.popBackStack() })
        LoadingBox(modifier = Modifier.fillMaxSize(), showLoading = isLoading) {
            LazyColumn(content = {
                items(appInfoList) { appInfo ->
                    val isChecked = blindApps.contains(appInfo.packageName)
                    AppInfoColumnItem(appInfo, isChecked, onClick = { onAppCheckedChange(appInfo, !isChecked) })
                }
                item { Box(Modifier.navigationBarsPadding()) }
            })
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun AddBlindAppsScreenPreview() {
    val context = LocalContext.current
    val data = AppInfo(appIcon = ColorDrawable(), packageName = BuildConfig.APPLICATION_ID,
        appName = context.getString(R.string.app_name), sharedUserId = null,
        isXposedModule = true, isSystemApp = false, applicationInfo = ApplicationInfo())
    AddBlindAppsContent(listOf(data, data), setOf(BuildConfig.APPLICATION_ID), false, { _, _ -> })
}
