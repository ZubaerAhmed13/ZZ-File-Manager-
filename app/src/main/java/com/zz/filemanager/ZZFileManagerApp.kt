package com.zz.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.model.ThemeMode
import com.zz.filemanager.core.util.BrowserLocationCodec
import com.zz.filemanager.feature.browser.BrowserOperationsViewModel
import com.zz.filemanager.feature.browser.BrowserScreen
import com.zz.filemanager.feature.browser.BrowserViewModel
import com.zz.filemanager.feature.home.HomeScreen
import com.zz.filemanager.feature.home.HomeViewModel
import com.zz.filemanager.feature.settings.SettingsScreen
import com.zz.filemanager.feature.settings.SettingsViewModel
import com.zz.filemanager.ui.theme.ZZFileManagerTheme

@Composable
fun ZZFileManagerApp(container: AppContainer) {
    val theme by container.preferences.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    ZZFileManagerTheme(theme) {
        val nav = rememberNavController()
        val context = LocalContext.current
        fun openLocation(location: com.zz.filemanager.core.model.BrowserLocation) {
            nav.navigate("browser?location=${Uri.encode(BrowserLocationCodec.encode(location))}")
        }
        fun requestBroadAccess() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }

        LaunchedEffect(Unit) {
            container.operationController.initialize()
            container.storage.restorableLastLocation()?.let(::openLocation)
        }

        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.storage, container.preferences))
                HomeScreen(vm, ::openLocation, onOpenSettings = { nav.navigate("settings") })
            }
            composable(
                route = "browser?location={location}",
                arguments = listOf(navArgument("location") { type = NavType.StringType }),
            ) { backStack ->
                val encoded = backStack.arguments?.getString("location").orEmpty()
                val location = BrowserLocationCodec.decode(encoded) ?: return@composable
                val vm: BrowserViewModel = viewModel(
                    key = "browser:${location.identity}",
                    factory = BrowserViewModel.Factory(container.storage, container.preferences),
                )
                val operationsVm: BrowserOperationsViewModel = viewModel(
                    key = "operations:${location.identity}",
                    factory = BrowserOperationsViewModel.Factory(
                        container.operationController,
                        container.operationClipboard,
                        container.storage,
                    ),
                )
                BrowserScreen(
                    viewModel = vm,
                    operationsViewModel = operationsVm,
                    initialLocation = location,
                    thumbnails = container.thumbnails,
                    onExitBrowser = { nav.popBackStack() },
                    onRequestStorageAccess = ::requestBroadAccess,
                )
            }
            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container.storage, container.preferences))
                SettingsScreen(vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
