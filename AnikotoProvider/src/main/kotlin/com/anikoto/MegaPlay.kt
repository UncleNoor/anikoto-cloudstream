package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Embed player used by every Anikoto server (HD-1, HD-2, Vidstream-1/2).
 *
 * Flow: embed page -> data-id -> /stream/getSources?id=… -> "enc" field, which is
 * base64url(AES-256-CBC(json)) using the key/IV shipped in megaplay's lib/app.main.js.
 */
open class MegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = extract(url, referer, name, subtitleCallback, callback)

    suspend fun extract(
        url: String,
        referer: String?,
        linkName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = app.get(url, referer = referer ?: "$mainUrl/").document
        val id = embed.selectFirst("#megaplay-player, [data-id]")?.attr("data-id")
            ?.takeIf { it.isNotBlank() } ?: return

        val sources = app.get(
            "$mainUrl/stream/getSources?id=$id",
            referer = url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Sources>() ?: return

        val files = buildList {
            sources.sources?.forEach { it.file?.let(::add) }
            sources.enc?.let { decryptSources(it) }?.let(::addAll)
        }.distinct()

        // Master playlist; the player handles variant/quality selection itself.
        files.forEach { file ->
            callback(
                newExtractorLink(name, linkName, file, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to mainUrl)
                }
            )
        }

        sources.tracks?.filter { it.kind == null || it.kind == "captions" || it.kind == "subtitles" }
            ?.forEach { track ->
                val file = track.file ?: return@forEach
                subtitleCallback(newSubtitleFile(track.label ?: "Unknown", file))
            }
    }

    private suspend fun decryptSources(enc: String): List<String>? {
        decryptWith(enc, DEFAULT_KEY, DEFAULT_IV)?.let { return it }
        // Keys rotated? Pull the current ones from the player bundle and retry.
        val js = runCatching { app.get("$mainUrl/lib/app.main.js").text }.getOrNull() ?: return null
        val key = Regex("""DOMAINS_ENC_KEY\s*=\s*"([^"]+)"""").find(js)?.groupValues?.get(1) ?: return null
        val iv = Regex("""DOMAINS_ENC_IV\s*=\s*"([^"]+)"""").find(js)?.groupValues?.get(1) ?: return null
        return decryptWith(enc, key, iv)
    }

    private fun decryptWith(enc: String, key: String, iv: String): List<String>? {
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray().copyOf(32), "AES"),
                IvParameterSpec(iv.toByteArray().copyOf(16))
            )
            val b64 = enc.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            val bytes = base64DecodeArray(b64)
            String(cipher.doFinal(bytes))
        }.getOrNull() ?: return null

        tryParseJson<Source>(plain)?.file?.let { return listOf(it) }
        tryParseJson<List<Source>>(plain)?.mapNotNull { it.file }?.takeIf { it.isNotEmpty() }?.let { return it }
        return Regex("""https?://[^"\s]+\.m3u8[^"\s]*""").findAll(plain).map { it.value }.toList()
            .takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val DEFAULT_KEY = "i?LMTAx0Q6,:}50U"
        private const val DEFAULT_IV = "W0;27ToaUpl_P%'c"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Sources(
        val sources: List<Source>? = null,
        val tracks: List<Track>? = null,
        val enc: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(val file: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
    )
}
