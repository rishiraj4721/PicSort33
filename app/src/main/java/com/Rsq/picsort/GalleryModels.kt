package com.Rsq.picsort

import android.net.Uri

data class GalleryPhoto(val id: Long, val uri: Uri)

data class PersonGroup(
    val id: Int,
    val photos: List<GalleryPhoto>,
)

data class ScanResult(
    val photos: List<GalleryPhoto>,
    val groups: List<PersonGroup>,
)