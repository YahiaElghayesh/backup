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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.ui.edit.PhotoEditScreen
import com.elghayesh.gallerybackup.ui.edit.VideoTrimScreen
import com.elghayesh.gallerybackup.ui.gallery.FolderVisibilityExplorerScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryScreen
import com.elghayesh.gallerybackup.ui.gallery.GallerySettingsScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import com.elghayesh.gallerybackup.ui.settings.BackupFolderExplorerScreen
import com.elghayesh.gallerybackup.ui.settings.BackupSettingsScreen
import com.elghayesh.gallerybackup.ui.settings.BackupViewModel
import com.elghayesh.gallerybackup.ui.theme.AppTheme
import com.elghayesh.gallerybackup.ui.trash.TrashScreen
import com.elghayesh.gallerybackup.ui.update.AppUpdateController
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
    ) { result ->
        // MediaStore only actually trashes/deletes the files if the user approved the system
        // dialog -- on cancel, nothing happened, so MediaHub's own trash bookkeeping shouldn't
        // pretend otherwise.
        galleryViewModel.onDeleteConfirmed(approved = result.resultCode == android.app.Activity.RESULT_OK)
    }

    private val restoreConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        galleryViewModel.onRestoreConfirmed(approved = result.resultCode == android.app.Activity.RESULT_OK)
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
                    AppUpdateController()

                    var lastCrash by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        lastCrash = CrashReporter.consumeLastCrash(this@MainActivity)
                    }
                    lastCrash?.let { crashText ->
                        AlertDialog(
                            onDismissRequest = { lastCrash = null },
                            title = { Text("MediaHub crashed last time") },
                            text = {
                                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                                    Text(
                                        "Screenshot or copy this and send it over so the exact cause can be fixed.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    SelectionContainer {
                                        Text(crashText, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { lastCrash = null }) { Text("OK") }
                            },
                        )
                    }

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
                        galleryViewModel.restoreConsentRequests.collect { pendingIntent ->
                            restoreConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
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

/**
 * Every screen transition in this app's NavHost animates (the default Navigation Compose
 * crossfade/slide), which takes a moment to actually settle -- during that window the
 * destination that triggered it is in the STARTED lifecycle state, not yet RESUMED. A second
 * navigate() call landing in that window (a genuine double-tap, or a single tap somehow producing
 * two click events -- gesture ambiguity across a tap and a long-press timer racing each other can
 * do this) doesn't get rejected by NavController on its own: it's simply pushed as a second
 * transition on top of the first, before the first one ever finished. That's what was actually
 * behind three seemingly unrelated glitches all being reported for the same action of opening a
 * folder: the second navigate() interrupting the first's still-playing transition is why the
 * animation was inconsistent (sometimes visibly cut short, sometimes not triggered at all if it
 * landed early enough); and since a folder's own screen is often already composed and laid out
 * behind that still-finishing transition, a second click can land on ITS content instead of the
 * tile that was actually tapped -- opening a media item that happens to be at that same screen
 * position instead of the folder, or a different folder tile than the one under the finger. None
 * of this was a hit-testing bug in the gallery grid itself (already rewritten twice this session);
 * it's specifically about a second, redundant navigation call being allowed to fire while the
 * first hadn't settled yet. Routing every forward navigation through this guard -- which only
 * calls through once the current back stack entry has actually reached RESUMED, i.e. its own
 * transition is done -- makes a second call while one is still in flight a no-op instead of a
 * second, conflicting destination change.
 */
private fun NavController.navigateSafely(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route)
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
                    navController.navigateSafely("gallery/${URLEncoder.encode(newPath, "UTF-8")}")
                },
                onOpenMedia = { folderPath, index ->
                    navController.navigateSafely("viewer/${URLEncoder.encode(folderPath, "UTF-8")}/$index")
                },
                onOpenGallerySettings = { navController.navigateSafely("gallerySettings") },
                onOpenBackupSettings = { navController.navigateSafely("backupSettings") },
                onOpenTrash = { navController.navigateSafely("trash") },
                onEditPhoto = { folderPath, index ->
                    navController.navigateSafely("editPhoto/${URLEncoder.encode(folderPath, "UTF-8")}/$index")
                },
                onEditVideo = { folderPath, index ->
                    navController.navigateSafely("trimVideo/${URLEncoder.encode(folderPath, "UTF-8")}/$index")
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
                    navController.navigateSafely("editPhoto/${URLEncoder.encode(folderPath, "UTF-8")}/$itemIndex")
                },
                onEditVideo = { folderPath, itemIndex ->
                    navController.navigateSafely("trimVideo/${URLEncoder.encode(folderPath, "UTF-8")}/$itemIndex")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("backupSettings") {
            BackupSettingsScreen(
                backupViewModel = backupViewModel,
                onOpenFolderExplorer = { navController.navigateSafely("backupFolderExplorer") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("backupFolderExplorer") {
            BackupFolderExplorerScreen(
                galleryViewModel = galleryViewModel,
                backupViewModel = backupViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("gallerySettings") {
            GallerySettingsScreen(
                viewModel = galleryViewModel,
                onOpenFolderExplorer = { navController.navigateSafely("folderExplorer") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("folderExplorer") {
            FolderVisibilityExplorerScreen(
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
