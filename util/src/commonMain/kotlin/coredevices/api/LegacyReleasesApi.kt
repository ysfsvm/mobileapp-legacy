package coredevices.api

import coredevices.util.CommonBuildKonfig
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A release of the PebbleOS port to the legacy watches, as GitHub lists it. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    @SerialName("html_url") val pageUrl: String,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList(),
)

@Serializable
data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

interface LegacyReleasesApi {
    /** The port's most recent releases, newest first. */
    suspend fun releases(): List<GithubRelease>

    /** The file behind one of a release's [GithubReleaseAsset.url]s. */
    suspend fun download(url: String): ByteArray
}

class LegacyReleasesApiImpl : ApiClient(CommonBuildKonfig.USER_AGENT_VERSION), LegacyReleasesApi {
    override suspend fun releases(): List<GithubRelease> {
        val resp = client.get(Url(RELEASES_URL)) {
            header("Accept", "application/vnd.github+json")
            parameter("per_page", 20)
        }
        if (!resp.status.isSuccess()) throw Exception("Failed to list the port's releases: ${resp.status}")
        return resp.body()
    }

    override suspend fun download(url: String): ByteArray {
        val resp = client.get(Url(url))
        if (!resp.status.isSuccess()) throw Exception("Failed to download $url: ${resp.status}")
        return resp.body()
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/ysfsvm/PebbleOS-legacy/releases"
    }
}
