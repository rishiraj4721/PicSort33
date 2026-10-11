package com.Rsq.picsort

import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportState(
    val isExporting: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val message: String? = null,
)

data class ServerHealthStatus(
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val detector: String? = null,
    val embedder: String? = null,
    val embeddingDim: Int? = null,
    val errorMessage: String? = null,
)

data class GalleryUiState(
    val hasScanned: Boolean = false,
    val isScanning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val photos: List<GalleryPhoto> = emptyList(),
    val groups: List<PersonGroup> = emptyList(),
    val hasError: Boolean = false,
    val errorMessage: String? = null,
    val selectedFolderUri: Uri? = null,
    val selectedFolderName: String? = null,
    val exportState: ExportState = ExportState(),
    val similarityThreshold: Float = 0.45f,
    val serverUrl: String = "http://10.0.2.2:8000",
    val apiKey: String = "",
    val clusteringAlgo: String = "chinese_whispers",
    val availableAlgos: List<String> = listOf("chinese_whispers", "dbscan"),
    val serverHealth: ServerHealthStatus = ServerHealthStatus(),
)

class GalleryViewModel(
    private val application: Application,
    private val galleryScanner: GalleryScanner,
    private val serverClient: FaceServerClient = FaceServerClient(),
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("picsort_prefs", Context.MODE_PRIVATE)
    private val cacheManager = ScanCacheManager(application)

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSavedSettingsAndCache()
        checkServerConnection()
    }

    private fun loadSavedSettingsAndCache() {
        val threshold = prefs.getFloat("similarity_threshold", 0.45f)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000"
        val apiKey = prefs.getString("api_key", "") ?: ""
        val algo = prefs.getString("clustering_algo", "chinese_whispers") ?: "chinese_whispers"

        val savedUriString = prefs.getString("selected_folder_uri", null)
        var selectedFolderUri: Uri? = null
        var selectedFolderName: String? = null

        if (savedUriString != null) {
            val savedUri = Uri.parse(savedUriString)
            val hasPersistedPermission = application.contentResolver.persistedUriPermissions.any {
                it.uri == savedUri && it.isReadPermission
            }
            if (hasPersistedPermission) {
                selectedFolderUri = savedUri
                selectedFolderName = getFolderName(application, savedUri)
            } else {
                prefs.edit().remove("selected_folder_uri").apply()
            }
        }

        val sourceKey = selectedFolderUri?.toString() ?: "all_photos"
        val cachedResult = cacheManager.loadScanResult(sourceKey)

        _uiState.update {
            it.copy(
                selectedFolderUri = selectedFolderUri,
                selectedFolderName = selectedFolderName,
                photos = cachedResult?.photos ?: emptyList(),
                groups = cachedResult?.groups ?: emptyList(),
                hasScanned = cachedResult != null,
                similarityThreshold = threshold,
                serverUrl = serverUrl,
                apiKey = apiKey,
                clusteringAlgo = algo,
            )
        }
    }

    fun checkServerConnection() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(serverHealth = it.serverHealth.copy(isLoading = true, errorMessage = null))
            }
            val currentUrl = _uiState.value.serverUrl
            val currentApiKey = _uiState.value.apiKey

            try {
                val health = serverClient.getHealth(currentUrl, currentApiKey)
                val algosResp = runCatching {
                    serverClient.getClusterAlgos(currentUrl, currentApiKey)
                }.getOrNull()

                val algos = algosResp?.algos?.map { it.name } ?: listOf("chinese_whispers", "dbscan")

                _uiState.update {
                    it.copy(
                        availableAlgos = algos,
                        serverHealth = ServerHealthStatus(
                            isConnected = true,
                            isLoading = false,
                            detector = health.detector,
                            embedder = health.embedder,
                            embeddingDim = health.embeddingDim,
                            errorMessage = null,
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        serverHealth = ServerHealthStatus(
                            isConnected = false,
                            isLoading = false,
                            errorMessage = e.message ?: "Failed to connect to server",
                        )
                    )
                }
            }
        }
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
        _uiState.update { it.copy(serverUrl = url) }
        checkServerConnection()
    }

    fun setApiKey(key: String) {
        prefs.edit().putString("api_key", key).apply()
        _uiState.update { it.copy(apiKey = key) }
        checkServerConnection()
    }

    fun setClusteringAlgo(algo: String) {
        prefs.edit().putString("clustering_algo", algo).apply()
        _uiState.update { it.copy(clusteringAlgo = algo) }
        recluster()
    }

    fun setSimilarityThreshold(threshold: Float) {
        prefs.edit().putFloat("similarity_threshold", threshold).apply()
        _uiState.update { it.copy(similarityThreshold = threshold) }
        recluster()
    }

    private fun recluster() {
        val photos = _uiState.value.photos
        if (photos.isEmpty()) return

        val serverUrl = _uiState.value.serverUrl
        val apiKey = _uiState.value.apiKey
        val threshold = _uiState.value.similarityThreshold
        val algo = _uiState.value.clusteringAlgo

        viewModelScope.launch(Dispatchers.IO) {
            val groups = galleryScanner.recluster(
                photos = photos,
                serverUrl = serverUrl,
                apiKey = apiKey,
                threshold = threshold,
                clusteringAlgo = algo,
            )
            _uiState.update { it.copy(groups = groups) }

            val sourceKey = _uiState.value.selectedFolderUri?.toString() ?: "all_photos"
            cacheManager.saveScanResult(sourceKey, ScanResult(photos, groups))
        }
    }

    fun selectFolder(uri: Uri) {
        val folderName = getFolderName(application, uri)
        prefs.edit().putString("selected_folder_uri", uri.toString()).apply()

        val cachedResult = cacheManager.loadScanResult(uri.toString())
        _uiState.update {
            it.copy(
                selectedFolderUri = uri,
                selectedFolderName = folderName,
                photos = cachedResult?.photos ?: emptyList(),
                groups = cachedResult?.groups ?: emptyList(),
                hasScanned = cachedResult != null,
            )
        }
        scan()
    }

    fun clearSelectedFolder() {
        prefs.edit().remove("selected_folder_uri").apply()
        val cachedResult = cacheManager.loadScanResult("all_photos")
        _uiState.update {
            it.copy(
                selectedFolderUri = null,
                selectedFolderName = null,
                photos = cachedResult?.photos ?: emptyList(),
                groups = cachedResult?.groups ?: emptyList(),
                hasScanned = cachedResult != null,
            )
        }
        scan()
    }

    private var scanJob: Job? = null

    fun scan() {
        scanJob?.cancel()

        val folderUri = _uiState.value.selectedFolderUri
        val threshold = _uiState.value.similarityThreshold
        val serverUrl = _uiState.value.serverUrl
        val apiKey = _uiState.value.apiKey
        val algo = _uiState.value.clusteringAlgo
        val sourceKey = folderUri?.toString() ?: "all_photos"

        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    hasError = false,
                    errorMessage = null,
                    completed = 0,
                    total = 0,
                )
            }
            try {
                val result = galleryScanner.scan(
                    folderUri = folderUri,
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    threshold = threshold,
                    clusteringAlgo = algo,
                    onProgress = { completed, total, currentPhotos, currentGroups ->
                        _uiState.update {
                            it.copy(
                                completed = completed,
                                total = total,
                                photos = currentPhotos,
                                groups = currentGroups,
                                hasScanned = true,
                            )
                        }
                    }
                )

                cacheManager.saveScanResult(sourceKey, result)
                _uiState.update {
                    it.copy(photos = result.photos, groups = result.groups, hasScanned = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        hasError = true,
                        errorMessage = e.message ?: "Error scanning photos with server",
                    )
                }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun exportPhotos(
        photos: List<GalleryPhoto>,
        destinationTreeUri: Uri,
        deleteOriginals: Boolean,
        onPendingDeleteRequest: ((PendingIntent) -> Unit)? = null,
    ) {
        if (photos.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    exportState = ExportState(
                        isExporting = true,
                        completed = 0,
                        total = photos.size,
                    )
                )
            }

            var successCount = 0
            val resolver = application.contentResolver
            val failedToDeleteUris = mutableListOf<Uri>()
            val successfullyMovedPhotoIds = mutableSetOf<Long>()

            try {
                val rootDocId = if (DocumentsContract.isTreeUri(destinationTreeUri)) {
                    DocumentsContract.getTreeDocumentId(destinationTreeUri)
                } else {
                    DocumentsContract.getDocumentId(destinationTreeUri)
                }

                photos.forEachIndexed { index, photo ->
                    try {
                        val fileName = getPhotoFileName(resolver, photo.uri, "photo_${photo.id}.jpg")
                        val mimeType = resolver.getType(photo.uri) ?: "image/jpeg"
                        val targetDirUri = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, rootDocId)
                        val newDocUri = DocumentsContract.createDocument(resolver, targetDirUri, mimeType, fileName)

                        if (newDocUri != null) {
                            resolver.openInputStream(photo.uri)?.use { input ->
                                resolver.openOutputStream(newDocUri)?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            successCount++

                            if (deleteOriginals) {
                                val deleted = try {
                                    if (DocumentsContract.isDocumentUri(application, photo.uri)) {
                                        DocumentsContract.deleteDocument(resolver, photo.uri)
                                    } else {
                                        val rows = resolver.delete(photo.uri, null, null)
                                        rows > 0
                                    }
                                } catch (_: Exception) {
                                    false
                                }

                                if (deleted) {
                                    successfullyMovedPhotoIds.add(photo.id)
                                } else {
                                    failedToDeleteUris.add(photo.uri)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    _uiState.update {
                        it.copy(exportState = it.exportState.copy(completed = index + 1))
                    }
                }

                val actionName = if (deleteOriginals) "Moved" else "Copied"
                var resultMessage = "$actionName $successCount of ${photos.size} photos to folder."

                if (deleteOriginals && failedToDeleteUris.isNotEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && onPendingDeleteRequest != null) {
                        try {
                            val pendingIntent = MediaStore.createDeleteRequest(resolver, failedToDeleteUris)
                            withContext(Dispatchers.Main) {
                                onPendingDeleteRequest(pendingIntent)
                            }
                        } catch (_: Exception) {
                            resultMessage += " Some original photos require manual deletion."
                        }
                    } else {
                        resultMessage += " Some original photos could not be deleted."
                    }
                }

                if (deleteOriginals && successfullyMovedPhotoIds.isNotEmpty()) {
                    val updatedPhotos = _uiState.value.photos.filterNot { it.id in successfullyMovedPhotoIds }
                    val updatedGroups = _uiState.value.groups.mapNotNull { group ->
                        val remaining = group.photos.filterNot { it.id in successfullyMovedPhotoIds }
                        if (remaining.isNotEmpty()) group.copy(photos = remaining) else null
                    }

                    _uiState.update {
                        it.copy(
                            photos = updatedPhotos,
                            groups = updatedGroups,
                        )
                    }

                    val sourceKey = _uiState.value.selectedFolderUri?.toString() ?: "all_photos"
                    cacheManager.saveScanResult(sourceKey, ScanResult(updatedPhotos, updatedGroups))
                }

                _uiState.update {
                    it.copy(exportState = ExportState(isExporting = false, message = resultMessage))
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(exportState = ExportState(isExporting = false, message = "Export error: ${e.localizedMessage}"))
                }
            }
        }
    }

    fun clearExportMessage() {
        _uiState.update { it.copy(exportState = it.exportState.copy(message = null)) }
    }

    private fun getPhotoFileName(resolver: ContentResolver, uri: Uri, fallback: String): String {
        return try {
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    MediaStore.Images.Media.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx1 = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idx2 = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val name1 = if (idx1 != -1 && !cursor.isNull(idx1)) cursor.getString(idx1) else null
                    val name2 = if (idx2 != -1 && !cursor.isNull(idx2)) cursor.getString(idx2) else null
                    name1 ?: name2 ?: fallback
                } else fallback
            } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun getFolderName(context: Context, treeUri: Uri): String {
        return try {
            val rootDocId = if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(treeUri)
            }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else null
                } else null
            } ?: rootDocId.substringAfterLast(":", rootDocId)
        } catch (_: Exception) {
            "Selected Folder"
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            val serverClient = FaceServerClient()
            val scanner = GalleryScanner(application, serverClient)
            return GalleryViewModel(application, scanner, serverClient) as T
        }
    }
}
