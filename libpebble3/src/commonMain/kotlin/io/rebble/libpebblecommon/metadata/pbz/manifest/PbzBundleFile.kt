package io.rebble.libpebblecommon.metadata.pbz.manifest

import kotlinx.serialization.Serializable

/** A file the bundle carries for the watch's filesystem, installed as [target] after the update. */
@Serializable
data class PbzBundleFile(
    val name: String,
    val target: String,
    val size: Long,
    val crc: Long,
)
