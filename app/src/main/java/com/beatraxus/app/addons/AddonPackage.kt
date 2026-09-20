package com.beatraxus.app.addons

import java.io.File

data class AddonManifest(
    val id: String,
    val displayName: String,
    val mediaType: String,
    val protocol: String,
    val authType: String,
    val iconFile: String? = null
)

data class AddonPackage(
    val manifest: AddonManifest,
    val directory: File
)
