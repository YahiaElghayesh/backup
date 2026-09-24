package com.elghayesh.gallerybackup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.allItemsRecursive
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.security.AppLockGateScreen
import com.elghayesh.gallerybackup.security.AuthPromptHost
import com.elghayesh.gallerybackup.ui.collage.CollageScreen
import com.elghayesh.gallerybackup.ui.edit.VideoMergeScreen
import com.elghayesh.gallerybackup.ui.edit.PhotoEditScreen
import com.elghayesh.gallerybackup.ui.edit.VideoTrimScreen
import com.elghayesh.gallerybackup.ui.gallery.FolderVisibilityExplorerScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryScreen
import com.elghayesh.gallerybackup.ui.gallery.GallerySettingsScreen
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import com.elghayesh.gallerybackup.ui.gallery.effectiveFolderSort
import com.elghayesh.gallerybackup.ui.gallery.sortedMedia
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

class MainActivity : FragmentActivity() {

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

                    val appLockEnabled by galleryViewModel.appLockEnabled.collectAsState()
                    // Not tied to appLockEnabled's own initial value -- starts false regardless, so a
                    // user WITHOUT app lock turned on never sees a spurious lock screen while this
                    // setting is still loading from disk; once appLockEnabled itself loads in true,
                    // this flips the gate on for real.
                    var isUnlocked by remember { mutableStateOf(false) }
                    DisposableEffect(Unit) {
                        // Re-locks every time the app leaves the foreground, so returning to it
                        // (not just a fresh cold start) asks again -- otherwise app lock would only
                        // ever matter once, the very first launch after enabling it.
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP) isUnlocked = false
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }

                    // Mounted unconditionally (not just while the gate itself is showing) --
                    // folder-lock and hidden-items-lock checks from deep inside AppNavHost call
                    // GalleryViewModel.requestAuth too, and need this host available to actually
                    // resolve them regardless of whether the app-wide gate is up.
                    AuthPromptHost(galleryViewModel)

                    if (appLockEnabled && !isUnlocked) {
                        AppLockGateScreen(viewModel = galleryViewModel, onUnlocked = { isUnlocked = true })
                    } else if (hasPermission) {
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
 * Three seemingly unrelated glitches all reported for the same action of opening a folder --
 * opening a media item instead, opening a different folder, and the transition animation
 * sometimes not playing -- all actually traced back to the default Navigation Compose
 * crossfade: it keeps the outgoing screen composed (and, critically, still hit-testable) while
 * the incoming one fades in on top, so a second tap landing in that overlap window could resolve
 * against whatever ends up on top there instead of the tile actually under the finger. Gating
 * navigate() calls on the destination reaching the RESUMED lifecycle state (this function's
 * previous approach) papered over that by refusing ANY second navigate() until the whole ~300ms
 * transition settled -- which also swallowed a person's own deliberate fast taps when quickly
 * opening and closing folders, since those legitimately land inside that same window.
 *
 * The actual fix is [AppNavHost] passing `EnterTransition.None`/`ExitTransition.None` for every
 * destination, which removes the crossfade (and the animation-consistency complaint along with
 * it): a new destination is composed and made exclusively interactive in the same frame, with no
 * window where the outgoing screen can still catch a stray tap. That leaves this guard only a
 * much smaller job -- filtering a genuine single-tap-fires-twice artifact (gesture ambiguity
 * between a tap and a long-press timer racing each other can occasionally do this), which shows
 * up as two navigate() calls mere milliseconds apart -- so it only needs to reject a second call
 * within a short real-time window, short enough to never interfere with someone deliberately
 * tapping through folders quickly.
 */
private const val NAVIGATE_DEBOUNCE_MS = 150L
private var lastNavigateAtMs = 0L

private fun NavController.navigateSafely(route: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastNavigateAtMs < NAVIGATE_DEBOUNCE_MS) return
    lastNavigateAtMs = now
    navigate(route)
}

@Composable
private fun AppNavHost(galleryViewModel: GalleryViewModel, backupViewModel: BackupViewModel) {
    val navController = rememberNavController()
    // No crossfade/slide between destinations -- see navigateSafely's doc comment above for why
    // an animated transition (which keeps the outgoing screen composed and hit-testable while the
    // incoming one fades in) was the actual root cause of taps resolving against the wrong
    // screen. An instant cut has no such overlap window, and also means there's no animation left
    // to look inconsistent.
    NavHost(
        navController = navController,
        startDestination = "gallery/{path}",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
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
                onOpenCollage = { ids -> navController.navigateSafely("collage/${ids.joinToString(",")}") },
                onOpenMergeVideos = { ids -> navController.navigateSafely("mergeVideos/${ids.joinToString(",")}") },
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
                onOpenBackupSettings = { navController.navigateSafely("backupSettings") },
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
            val folderSort by galleryViewModel.folderSort.collectAsState()
            val folderSortOverrides by galleryViewModel.folderSortOverrides.collectAsState()
            // Same effectiveFolderSort/sortedMedia as GalleryScreen -- see sortedMedia's own doc
            // comment for why a hardcoded newest-first here could open the wrong item.
            val order = effectiveFolderSort(path, folderSort, folderSortOverrides)
            // Resolved ONCE (the first time `root` has loaded) and then frozen -- previously this
            // re-resolved item-by-index on every recomposition, so a background rescan that
            // reordered or added/removed items while a photo was mid-edit (any refresh() call --
            // e.g. the pull-to-refresh, a sync completing, or even this very edit's own save)
            // could silently swap in a completely different photo at that same index, mid-edit,
            // with no navigation action from the user at all.
            var item by remember(path, index) { mutableStateOf<MediaItem?>(null) }
            LaunchedEffect(root) {
                if (item == null) {
                    item = sortedMedia(root?.findNode(path)?.items ?: emptyList(), order).getOrNull(index)
                }
            }
            item?.let { resolvedItem ->
                PhotoEditScreen(item = resolvedItem, viewModel = galleryViewModel, onDone = { navController.popBackStack() })
            }
        }
        composable(
            route = "collage/{ids}",
            arguments = listOf(navArgument("ids") { type = NavType.StringType }),
        ) { backStackEntry ->
            val ids = (backStackEntry.arguments?.getString("ids") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            val root by galleryViewModel.visibleRoot.collectAsState()
            // Order matches the id list (selection order), not whatever order allItemsRecursive
            // happens to walk the tree in.
            val idOrder = ids.withIndex().associate { (index, id) -> id to index }
            val items = root?.allItemsRecursive()
                ?.filter { it.id in idOrder }
                ?.sortedBy { idOrder[it.id] }
                ?: emptyList()
            CollageScreen(items = items, viewModel = galleryViewModel, onDone = { navController.popBackStack() })
        }
        composable(
            route = "mergeVideos/{ids}",
            arguments = listOf(navArgument("ids") { type = NavType.StringType }),
        ) { backStackEntry ->
            val ids = (backStackEntry.arguments?.getString("ids") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            val root by galleryViewModel.visibleRoot.collectAsState()
            // Order matches the id list (selection order) -- the merge screen's own reorder
            // controls take over from there.
            val idOrder = ids.withIndex().associate { (index, id) -> id to index }
            val items = root?.allItemsRecursive()
                ?.filter { it.id in idOrder }
                ?.sortedBy { idOrder[it.id] }
                ?: emptyList()
            VideoMergeScreen(items = items, viewModel = galleryViewModel, onDone = { navController.popBackStack() })
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
            val folderSort by galleryViewModel.folderSort.collectAsState()
            val folderSortOverrides by galleryViewModel.folderSortOverrides.collectAsState()
            val order = effectiveFolderSort(path, folderSort, folderSortOverrides)
            // Resolved once and frozen -- see the identical comment on the editPhoto route above.
            var item by remember(path, index) { mutableStateOf<MediaItem?>(null) }
            LaunchedEffect(root) {
                if (item == null) {
                    item = sortedMedia(root?.findNode(path)?.items ?: emptyList(), order).getOrNull(index)
                }
            }
            item?.let { resolvedItem ->
                VideoTrimScreen(item = resolvedItem, viewModel = galleryViewModel, onDone = { navController.popBackStack() })
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
