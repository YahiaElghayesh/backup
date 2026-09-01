package com.elghayesh.gallerybackup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.ui.edit.PhotoEditScreen
import com.elghayesh.gallerybackup.ui.edit.VideoTrimScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryScreen
import com.elghayesh.gallerybackup.ui.gallery.GallerySettingsScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import com.elghayesh.gallerybackup.ui.settings.BackupSettingsScreen
import com.elghayesh.gallerybackup.ui.settings.BackupViewModel
import com.elghayesh.gallerybackup.ui.theme.AppTheme
import com.elghayesh.gallerybackup.ui.trash.TrashScreen
import com.elghayesh.gallerybackup.ui.viewer.MediaViewerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val galleryViewModel: GalleryViewModel by viewModels()
    private val backupViewModel: BackupViewModel by viewModels()

    /** Set from [onNewIntent]/[onCreate] when the OneDrive sign-in browser tab sends the user back. */
    private val pendingOneDriveRedirect = MutableStateFlow<Uri?>(null)

    private val driveConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        backupViewModel.onConsentResult(result.data)
    }

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // Whether confirmed or cancelled, a rescan is harmless and keeps the gallery in sync.
        galleryViewModel.onDeleteConfirmed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        capturePendingOneDriveRedirect(intent)
    }

    private fun capturePendingOneDriveRedirect(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && uri.scheme == "mediahub" && uri.host == "oauth") {
            pendingOneDriveRedirect.value = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capturePendingOneDriveRedirect(intent)
        setContent {
            val themeMode by galleryViewModel.themeMode.collectAsState()
            val accentColor by galleryViewModel.accentColor.collectAsState()

            AppTheme(themeMode = themeMode, accentColor = accentColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LaunchedEffect(Unit) {
                        backupViewModel.consentRequests.collect { pendingIntent ->
                            driveConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                        }
                    }
                    LaunchedEffect(Unit) {
                        galleryViewModel.deleteConsentRequests.collect { pendingIntent ->
                            deleteConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                        }
                    }
                    LaunchedEffect(Unit) {
                        backupViewModel.oneDriveAuthRequests.collect { intent -> startActivity(intent) }
                    }
                    LaunchedEffect(Unit) {
                        pendingOneDriveRedirect.collect { uri ->
                            if (uri != null) {
                                backupViewModel.onOneDriveRedirect(uri)
                                pendingOneDriveRedirect.value = null
                            }
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
                onOpenGallerySettings = { navController.navigate("gallerySettings") },
                onOpenBackupSettings = { navController.navigate("backupSettings") },
                onOpenTrash = { navController.navigate("trash") },
                onEditPhoto = { folderPath, index ->
                    navController.navigate("editPhoto/${URLEncoder.encode(folderPath, "UTF-8")}/$index")
                },
                onEditVideo = { folderPath, index ->
                    navController.navigate("trimVideo/${URLEncoder.encode(folderPath, "UTF-8")}/$index")
                },
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
                onEditPhoto = { folderPath, itemIndex ->
                    navController.navigate("editPhoto/${URLEncoder.encode(folderPath, "UTF-8")}/$itemIndex")
                },
                onEditVideo = { folderPath, itemIndex ->
                    navController.navigate("trimVideo/${URLEncoder.encode(folderPath, "UTF-8")}/$itemIndex")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("backupSettings") {
            BackupSettingsScreen(
                galleryViewModel = galleryViewModel,
                backupViewModel = backupViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("gallerySettings") {
            GallerySettingsScreen(
                viewModel = galleryViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("trash") {
            TrashScreen(
                viewModel = galleryViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "editPhoto/{path}/{index}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", "UTF-8")
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            val root by galleryViewModel.visibleRoot.collectAsState()
            val item = root?.findNode(path)?.items?.sortedByDescending { it.dateModifiedSec }?.getOrNull(index)
            if (item != null) {
                PhotoEditScreen(item = item, viewModel = galleryViewModel, onDone = { navController.popBackStack() })
            }
        }
        composable(
            route = "trimVideo/{path}/{index}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", "UTF-8")
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            val root by galleryViewModel.visibleRoot.collectAsState()
            val item = root?.findNode(path)?.items?.sortedByDescending { it.dateModifiedSec }?.getOrNull(index)
            if (item != null) {
                VideoTrimScreen(item = item, viewModel = galleryViewModel, onDone = { navController.popBackStack() })
            }
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
        Text("MediaHub needs permission to see your photos and videos to display and back them up.")
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
