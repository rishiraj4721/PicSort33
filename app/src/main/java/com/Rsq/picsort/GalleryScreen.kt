package com.Rsq.picsort

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.util.Locale

private val Canvas = Color(0xFFF1F4EE)
private val Ink = Color(0xFF193229)
private val Leaf = Color(0xFF446B53)
private val Coral = Color(0xFFD96D55)
private val Mist = Color(0xFFE3E9DF)
private val Muted = Color(0xFF66776C)

data class PhotoDetails(
    val photo: GalleryPhoto,
    val name: String,
    val path: String,
    val sizeString: String,
    val resolutionString: String,
)

@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var permissionGranted by remember {
        mutableStateOf(hasImageAccess(context))
    }
    var selectedGroup by remember { mutableStateOf<PersonGroup?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var isMoveOperation by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var selectedPhotoForDetails by remember { mutableStateOf<GalleryPhoto?>(null) }

    var selectedPhotoIds by remember(selectedGroup) {
        mutableStateOf(selectedGroup?.photos?.map { it.id }?.toSet() ?: emptySet())
    }

    BackHandler(enabled = selectedPhotoForDetails != null || selectedGroup != null || showSettingsSheet || uiState.selectedFolderUri != null) {
        when {
            selectedPhotoForDetails != null -> selectedPhotoForDetails = null
            selectedGroup != null -> selectedGroup = null
            showSettingsSheet -> showSettingsSheet = false
            uiState.selectedFolderUri != null -> viewModel.clearSelectedFolder()
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted = hasImageAccess(context)
    }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.scan()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            viewModel.selectFolder(uri)
        }
    }

    val exportFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && selectedGroup != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}

            val targetPhotos = selectedGroup!!.photos.filter { it.id in selectedPhotoIds }
            viewModel.exportPhotos(
                photos = targetPhotos,
                destinationTreeUri = uri,
                deleteOriginals = isMoveOperation,
                onPendingDeleteRequest = { pendingIntent ->
                    deleteRequestLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                }
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = hasImageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val hasAccess = permissionGranted || uiState.selectedFolderUri != null

    LaunchedEffect(hasAccess, uiState.selectedFolderUri) {
        if (hasAccess && !uiState.hasScanned && !uiState.isScanning) {
            viewModel.scan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .safeDrawingPadding()
            .padding(top = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("PicSort", color = Leaf, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (selectedGroup != null) {
                IconButton(onClick = { selectedGroup = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back to people")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showSettingsSheet = !showSettingsSheet },
                        enabled = !uiState.isScanning,
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Face matching sensitivity",
                            tint = if (showSettingsSheet) Coral else Ink,
                        )
                    }
                    IconButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        enabled = !uiState.isScanning,
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "Select folder",
                            tint = if (uiState.selectedFolderUri != null) Coral else Ink,
                        )
                    }
                    IconButton(onClick = viewModel::scan, enabled = hasAccess && !uiState.isScanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan photos", tint = Ink)
                    }
                }
            }
        }

        if (showSettingsSheet && selectedGroup == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                color = Mist,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Face Matching Threshold",
                            color = Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showSettingsSheet = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Ink, modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        "Lower values group different angles/photos of the same person together.",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("More Grouping (Loose)", color = Leaf, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${(uiState.similarityThreshold * 100).toInt()}% Match",
                            color = Coral,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Strict Separate", color = Leaf, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Slider(
                        value = uiState.similarityThreshold,
                        onValueChange = { viewModel.setSimilarityThreshold(it) },
                        valueRange = 0.60f..0.92f,
                        colors = SliderDefaults.colors(
                            thumbColor = Coral,
                            activeTrackColor = Leaf,
                            inactiveTrackColor = Color.White
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        Text(
            text = selectedGroup?.let { "Person ${it.id + 1}" }
                ?: if (selectedTab == 0) "Your whole\nphoto story." else "Faces,\nfamiliar again.",
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp),
            color = Ink,
            fontSize = 34.sp,
            lineHeight = 37.sp,
            fontFamily = FontFamily.Serif,
        )

        val sourceText = when {
            selectedGroup != null -> "${selectedPhotoIds.size} of ${selectedGroup!!.photos.size} selected"
            uiState.selectedFolderUri != null -> "Folder: ${uiState.selectedFolderName ?: "Selected Folder"} (recursive)"
            selectedTab == 0 -> "A little order for your camera roll."
            else -> "People spotted across your photos."
        }
        Text(
            text = sourceText,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp),
            color = Muted,
            fontSize = 14.sp,
        )

        if (uiState.selectedFolderUri != null && permissionGranted && selectedGroup == null) {
            Text(
                text = "Switch to All Photos",
                color = Leaf,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(start = 22.dp, top = 4.dp)
                    .clickable { viewModel.clearSelectedFolder() },
            )
        }

        if (selectedPhotoForDetails != null) {
            SelectedPhotoDetailsCard(
                photo = selectedPhotoForDetails!!,
                onClose = { selectedPhotoForDetails = null }
            )
        }

        if (selectedGroup != null) {
            val totalInGroup = selectedGroup!!.photos.size
            val selectedCount = selectedPhotoIds.size

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (selectedCount == totalInGroup) "Deselect All" else "Select All",
                    fontSize = 13.sp,
                    color = Leaf,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        selectedPhotoIds = if (selectedCount == totalInGroup) {
                            emptySet()
                        } else {
                            selectedGroup!!.photos.map { it.id }.toSet()
                        }
                    }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isMoveOperation = false
                            exportFolderPickerLauncher.launch(null)
                        },
                        enabled = selectedCount > 0 && !uiState.exportState.isExporting,
                        colors = ButtonDefaults.buttonColors(containerColor = Leaf),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy ($selectedCount)", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            isMoveOperation = true
                            exportFolderPickerLauncher.launch(null)
                        },
                        enabled = selectedCount > 0 && !uiState.exportState.isExporting,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Move ($selectedCount)", fontSize = 12.sp)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Mist)
                    .padding(4.dp),
            ) {
                TabChoice("Photos", Icons.Default.Collections, selectedTab == 0, Modifier.weight(1f)) {
                    selectedTab = 0
                }
                TabChoice("People", Icons.Default.Face, selectedTab == 1, Modifier.weight(1f)) {
                    selectedTab = 1
                }
            }
        }

        if (uiState.exportState.isExporting) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 6.dp),
                color = Mist,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Coral,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Processing ${uiState.exportState.completed} of ${uiState.exportState.total} photos...",
                        color = Ink,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (uiState.exportState.message != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 6.dp),
                color = Leaf,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        uiState.exportState.message!!,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "OK",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { viewModel.clearExportMessage() }
                            .padding(start = 12.dp)
                    )
                }
            }
        }

        when {
            !hasAccess -> EmptyState(
                title = "Your photos stay yours.",
                message = "Allow photo access or choose a specific folder to scan images and find faces on this device.",
                primaryAction = "Allow photo access",
                onPrimaryAction = { permissionRequest.launch(imagePermissions()) },
                secondaryAction = "Select a folder",
                onSecondaryAction = { folderPickerLauncher.launch(null) },
            )
            uiState.isScanning -> ScanState(uiState.completed, uiState.total)
            uiState.hasError -> EmptyState(
                title = "Couldn't scan photos.",
                message = "Check photo access or folder permissions and try again.",
                primaryAction = "Try again",
                onPrimaryAction = viewModel::scan,
                secondaryAction = "Select folder",
                onSecondaryAction = { folderPickerLauncher.launch(null) },
            )
            !uiState.hasScanned -> ScanState(uiState.completed, uiState.total)
            selectedGroup != null -> PhotoGrid(
                photos = selectedGroup!!.photos,
                selectedDetailPhotoId = selectedPhotoForDetails?.id,
                selectable = true,
                selectedIds = selectedPhotoIds,
                onToggleSelect = { id ->
                    selectedPhotoIds = if (id in selectedPhotoIds) {
                        selectedPhotoIds - id
                    } else {
                        selectedPhotoIds + id
                    }
                },
                onPhotoClick = { photo -> selectedPhotoForDetails = photo }
            )
            selectedTab == 0 -> {
                if (uiState.photos.isEmpty()) {
                    EmptyState(
                        title = "No photos found.",
                        message = if (uiState.selectedFolderUri != null) "No images found in the selected folder or its subfolders." else "Images on this device will show up here.",
                        primaryAction = "Select another folder",
                        onPrimaryAction = { folderPickerLauncher.launch(null) },
                    )
                } else {
                    PhotoGrid(
                        photos = uiState.photos,
                        selectedDetailPhotoId = selectedPhotoForDetails?.id,
                        onPhotoClick = { photo -> selectedPhotoForDetails = photo }
                    )
                }
            }
            uiState.groups.isEmpty() -> EmptyState(
                title = "No faces found yet.",
                message = "Try another scan after adding a few photos with people in them.",
                primaryAction = "Rescan",
                onPrimaryAction = viewModel::scan,
                secondaryAction = "Select another folder",
                onSecondaryAction = { folderPickerLauncher.launch(null) },
            )
            else -> {
                Text(
                    "Possible matches · on-device estimate",
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
                    color = Muted,
                    fontSize = 12.sp,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(uiState.groups, key = { it.id }) { group ->
                        GroupTile(group) { selectedGroup = group }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPhotoDetailsCard(
    photo: GalleryPhoto,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val details = remember(photo) { getPhotoDetails(context, photo) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 6.dp),
        color = Mist,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = details.name,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = details.name,
                            color = Ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${details.sizeString}  ·  ${details.resolutionString}",
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close details", tint = Ink, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Path: ${details.path}",
                color = Leaf,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatPhotoPath(rawPath: String): String {
    if (rawPath.isBlank()) return "Unknown path"

    if (!rawPath.startsWith("content://") && !rawPath.startsWith("file://")) {
        val cleanPath = rawPath.replace("/storage/emulated/0/", "Internal Storage / ")
        return cleanPath.replace("/", " / ")
    }

    val decoded = Uri.decode(rawPath)

    return when {
        decoded.contains("/document/primary:") -> {
            val relPath = decoded.substringAfter("/document/primary:")
            "Internal Storage / " + relPath.replace("/", " / ")
        }
        decoded.contains("/tree/primary:") -> {
            val relPath = decoded.substringAfter("/tree/primary:")
            "Internal Storage / " + relPath.replace("/", " / ")
        }
        decoded.contains("/document/") -> {
            val docId = decoded.substringAfter("/document/")
            if (docId.contains(":")) {
                val volume = docId.substringBefore(":")
                val relPath = docId.substringAfter(":")
                val volumeName = if (volume.equals("primary", ignoreCase = true)) "Internal Storage" else volume
                "$volumeName / " + relPath.replace("/", " / ")
            } else {
                docId.replace("/", " / ")
            }
        }
        decoded.contains("media/external/images/media/") -> {
            val id = decoded.substringAfterLast("/")
            "MediaStore / Images / Photo #$id"
        }
        else -> {
            decoded.substringAfter("://").replace("/", " / ")
        }
    }
}

private fun getPhotoDetails(context: Context, photo: GalleryPhoto): PhotoDetails {
    val resolver = context.contentResolver
    var name = ""
    var sizeBytes: Long = 0
    var width = 0
    var height = 0
    var rawPath = photo.uri.toString()

    try {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(photo.uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idxName1 = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxName2 = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val idxSize1 = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxSize2 = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val idxWidth = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val idxHeight = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val idxData = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                val displayName1 = if (idxName1 != -1 && !cursor.isNull(idxName1)) cursor.getString(idxName1) else null
                val displayName2 = if (idxName2 != -1 && !cursor.isNull(idxName2)) cursor.getString(idxName2) else null
                name = displayName1 ?: displayName2 ?: ""

                val size1 = if (idxSize1 != -1 && !cursor.isNull(idxSize1)) cursor.getLong(idxSize1) else 0L
                val size2 = if (idxSize2 != -1 && !cursor.isNull(idxSize2)) cursor.getLong(idxSize2) else 0L
                sizeBytes = if (size1 > 0) size1 else size2

                if (idxWidth != -1 && !cursor.isNull(idxWidth)) width = cursor.getInt(idxWidth)
                if (idxHeight != -1 && !cursor.isNull(idxHeight)) height = cursor.getInt(idxHeight)
                if (idxData != -1 && !cursor.isNull(idxData)) {
                    val dataPath = cursor.getString(idxData)
                    if (!dataPath.isNullOrEmpty()) rawPath = dataPath
                }
            }
        }
    } catch (_: Exception) {}

    if (name.isEmpty()) {
        name = "photo_${photo.id}.jpg"
    }

    if (sizeBytes <= 0) {
        try {
            resolver.openFileDescriptor(photo.uri, "r")?.use { pfd ->
                sizeBytes = pfd.statSize
            }
        } catch (_: Exception) {}
    }

    if (width <= 0 || height <= 0) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(photo.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            width = options.outWidth
            height = options.outHeight
        } catch (_: Exception) {}
    }

    val formattedPath = formatPhotoPath(rawPath)
    val sizeStr = formatFileSize(sizeBytes)
    val resStr = if (width > 0 && height > 0) {
        val mp = (width.toDouble() * height.toDouble()) / 1_000_000.0
        if (mp >= 0.1) {
            "$width × $height (${String.format(Locale.US, "%.1f", mp)} MP)"
        } else {
            "$width × $height"
        }
    } else "Unknown resolution"

    return PhotoDetails(photo, name, formattedPath, sizeStr, resStr)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes bytes"
    }
}

@Composable
private fun TabChoice(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = if (selected) Coral else Muted)
        Text(
            label,
            modifier = Modifier.padding(start = 7.dp),
            color = if (selected) Ink else Muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PhotoGrid(
    photos: List<GalleryPhoto>,
    selectedDetailPhotoId: Long? = null,
    selectable: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
    onPhotoClick: (GalleryPhoto) -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(photos, key = { it.id }) { photo ->
            val isSelectedForExport = photo.id in selectedIds
            val isSelectedForDetails = photo.id == selectedDetailPhotoId

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (isSelectedForDetails) {
                            Modifier.border(2.dp, Coral, RoundedCornerShape(4.dp))
                        } else Modifier
                    )
                    .clickable {
                        onPhotoClick(photo)
                        if (selectable) {
                            onToggleSelect(photo.id)
                        }
                    }
            ) {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = "Photo",
                    modifier = Modifier.fillMaxSize(),
                )
                if (selectable) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (isSelectedForExport) Color.Black.copy(alpha = 0.2f) else Color.Transparent)
                            .padding(6.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Icon(
                            imageVector = if (isSelectedForExport) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (isSelectedForExport) "Selected" else "Not selected",
                            tint = if (isSelectedForExport) Coral else Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTile(group: PersonGroup, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = group.photos.firstOrNull()?.uri,
            contentDescription = "Photos of person ${group.id + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.92f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            "Person ${group.id + 1}",
            modifier = Modifier.padding(top = 8.dp),
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${group.photos.size} photos",
            modifier = Modifier.padding(top = 2.dp),
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ScanState(completed: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Coral, strokeWidth = 3.dp)
        Text(
            if (total == 0) "Finding your photos" else "Looking through $completed of $total photos",
            modifier = Modifier.padding(top = 18.dp),
            color = Ink,
            fontSize = 15.sp,
        )
        if (total > 0) {
            LinearProgressIndicator(
                progress = { completed.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                color = Leaf,
                trackColor = Mist,
            )
        }
        Text(
            "Face analysis stays on this phone.",
            modifier = Modifier.padding(top = 9.dp),
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    primaryAction: String? = null,
    onPrimaryAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = Mist, shape = RoundedCornerShape(50)) {
            Box(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Face, contentDescription = null, tint = Leaf, modifier = Modifier.size(25.dp))
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 18.dp),
            color = Ink,
            fontSize = 20.sp,
            fontFamily = FontFamily.Serif,
        )
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp),
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        if (primaryAction != null) {
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.padding(top = 18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(primaryAction)
            }
        }
        if (secondaryAction != null) {
            Button(
                onClick = onSecondaryAction,
                modifier = Modifier.padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Leaf),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(secondaryAction)
                }
            }
        }
    }
}

private fun imagePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasImageAccess(context: Context): Boolean {
    val hasFullAccess = ContextCompat.checkSelfPermission(
        context,
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    ) == PackageManager.PERMISSION_GRANTED
    val hasSelectedAccess = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED

    return hasFullAccess || hasSelectedAccess
}