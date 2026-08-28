package com.elghayesh.gallerybackup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elghayesh.gallerybackup.ui.gallery.GalleryScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import com.elghayesh.gallerybackup.ui.settings.BackupSettingsScreen
import com.elghayesh.gallerybackup.ui.settings.BackupViewModel
import com.elghayesh.gallerybackup.ui.viewer.MediaViewerScreen
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val galleryViewModel: GalleryViewModel by viewModels()
    private val backupViewModel: BackupViewModel by viewModels()

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        backupViewModel.onConsentResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LaunchedEffect(Unit) {
                        backupViewModel.consentRequests.collect { pendingIntent ->
                            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                        }
                    }

                    var hasPermission by remember { mutableStateOf(hasMediaPermission(this@MainActivity)) }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) {
                        hasPermission = hasMediaPermission(this@MainActivity)
                    }
                    LaunchedEffect(Unit) {
                        if (!hasPermission) permissionLauncher.launch(requiredMediaPermissions())
                    }

                    if (hasPermission) {
                        AppNavHost(galleryViewModel, backupViewModel)
                    } else {
                        PermissionRationaleScreen(
                            onRequestPermission = { permissionLauncher.launch(requiredMediaPermissions()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(galleryViewModel: GalleryViewModel, backupViewModel: BackupViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "gallery/{path}") {
        composable(
            route = "gallery/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
        ) { backStackEntry ->
            val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", "UTF-8")
            GalleryScreen(
                path = path,
                viewModel = galleryViewModel,
                onOpenFolder = { newPath ->
                    navController.navigate("gallery/${URLEncoder.encode(newPath, "UTF-8")}")
                },
                onOpenMedia = { folderPath, index ->
                    navController.navigate("viewer/${URLEncoder.encode(folderPath, "UTF-8")}/$index")
                },
                onOpenSettings = { navController.navigate("settings") },
                onNavigateUp = { navController.popBackStack() },
            )
        }
        composable(
            route = "viewer/{path}/{index}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", "UTF-8")
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            MediaViewerScreen(
                path = path,
                startIndex = index,
                viewModel = galleryViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            BackupSettingsScreen(
                galleryViewModel = galleryViewModel,
                backupViewModel = backupViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun PermissionRationaleScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gallery Backup needs permission to see your photos and videos to display and back them up.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant access")
        }
    }
}

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasMediaPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
