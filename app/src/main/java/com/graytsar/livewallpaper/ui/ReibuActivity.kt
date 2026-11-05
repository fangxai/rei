@file:OptIn(ExperimentalMaterial3Api::class)

package com.graytsar.livewallpaper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.graytsar.livewallpaper.navigation.PickerRoute
import com.graytsar.livewallpaper.navigation.SettingsRoute
import com.graytsar.livewallpaper.route.PickerScreenRoute
import com.graytsar.livewallpaper.route.SettingsScreenRoute
import com.graytsar.livewallpaper.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

// The scrims enableEdgeToEdge() uses by default behind three-button navigation.
private val NavigationBarLightScrim = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val NavigationBarDarkScrim = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)

@AndroidEntryPoint
class ReibuActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = uiState.isDarkModeEnabled

            // System bar icons follow the app's own dark mode setting, not the system's,
            // otherwise dark icons end up on the black dark-mode background.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = android.graphics.Color.TRANSPARENT,
                        darkScrim = android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = NavigationBarLightScrim,
                        darkScrim = NavigationBarDarkScrim,
                    ) { darkTheme },
                )
                onDispose {}
            }

            AppTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()
                ReiActivityScreen(
                    navController = navController
                )
            }
        }
    }
}

@Composable
fun ReiActivityScreen(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = PickerRoute
    ) {
        composable<PickerRoute> {
            PickerScreenRoute(
                onSettingsClick = { navController.navigate(SettingsRoute) }
            )
        }

        composable<SettingsRoute> {
            SettingsScreenRoute(
                onBackPress = { navController.popBackStack() }
            )
        }
    }
}

@Preview
@Composable
fun ReiActivityScreenPreview() {
    val navController = rememberNavController()
    AppTheme {
        ReiActivityScreen(
            navController = navController
        )
    }
}