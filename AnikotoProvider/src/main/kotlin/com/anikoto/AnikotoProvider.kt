package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.cz"
    override var name = "Anikoto"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val megaPlay = MegaPlay()
    private val xhr = mapOf("X-Requested-With" to "XMLHttpRequest")

    override val mainPage = mainPageOf(
        "latest-updated" to "Latest Updated",
        "new-release" to "New Releases",
        "most-viewed" to "Most Viewed",
        "status/currently-airing" to "Currently Airing",
        "type/movie" to "Movies",
        "status/not-yet-aired" to "Upcoming",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data}?page=$page").document
        val items = doc.select("#list-items .item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("ul.pagination a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$q&page=$page").document
        val items = doc.select("#list-items .item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("ul.pagination a[rel=next]") != null
        return newSearchResponseList(items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.name") ?: return null
        val href = fixUrl(link.attr("href")).substringBefore("/ep-")
        val title = link.text().trim().ifBlank { return null }
        val poster = selectFirst(".poster img")?.attr("src")
        val sub = selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dub = selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()
        val type = selectFirst(".poster .meta .right")?.text()?.trim()
        return newAnimeSearchResponse(title, href, getType(type)) {
            posterUrl = poster
            addDubStatus(dubExist = dub != null, subExist = sub != null, dubEpisodes = dub, subEpisodes = sub)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val showUrl = url.substringBefore("/ep-")
        val doc = app.get(showUrl).document

        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: throw ErrorLoadingException("Anime id not found")
        val info = doc.selectFirst("#w-info") ?: doc
        val titleEl = info.selectFirst("h1.title")
        val title = titleEl?.text()?.trim() ?: doc.title()
        val poster = info.selectFirst(".binfo .poster img")?.attr("src")
        val banner = doc.selectFirst("#player")?.attr("style")
            ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
        val plot = info.selectFirst(".synopsis .content")?.text()?.trim()

        fun meta(label: String): Element? = info.select(".bmeta .meta > div")
            .firstOrNull { it.ownText().trim().startsWith(label, ignoreCase = true) }
            ?.selectFirst("span")

        val type = meta("Type")?.text()?.trim()
        val year = (meta("Premiered")?.text() ?: meta("Aired")?.text())
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val status = when (meta("Status")?.text()?.trim()?.lowercase()) {
            "finished airing" -> ShowStatus.Completed
            "currently airing" -> ShowStatus.Ongoing
            else -> null
        }
        val genres = meta("Genres")?.select("a")?.map { it.text().trim() }
        val duration = meta("Duration")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        val recommendations = doc.select(".sidebar section").firstOrNull {
            it.selectFirst(".head .title")?.text()?.contains("Recommended", true) == true
        }?.select("a.item")?.mapNotNull { it.toSideResult() }

        val epHtml = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            referer = showUrl,
            headers = xhr
        ).parsedSafe<AjaxResponse>()?.result ?: ""
        val epAnchors = Jsoup.parse(epHtml).select("ul.ep-range li a[data-ids]")

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        var malId: Int? = null
        epAnchors.forEach { a ->
            val ids = a.attr("data-ids")
            val num = a.attr("data-num").toFloatOrNull()
            val epName = a.parent()?.attr("title")?.takeIf { it.isNotBlank() }
            if (malId == null) malId = a.attr("data-mal").toIntOrNull()
            fun make(kind: String) = newEpisode(EpisodeData(ids, kind).toJson()) {
                name = epName
                episode = num?.toInt()
            }
            if (a.attr("data-sub") == "1") subEpisodes += make("sub")
            if (a.attr("data-dub") == "1") dubEpisodes += make("dub")
            // Some entries (raw / unknown) carry neither flag; expose them as sub.
            if (a.attr("data-sub") != "1" && a.attr("data-dub") != "1") subEpisodes += make("sub")
        }

        val tvType = getType(type)
        return newAnimeLoadResponse(title, showUrl, tvType) {
            japName = titleEl?.attr("data-jp")?.takeIf { it.isNotBlank() && it != title }
            engName = title
            posterUrl = poster
            backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            tags = genres
            showStatus = status
            this.duration = duration
            this.recommendations = recommendations
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            addMalId(malId)
        }
    }

    private fun Element.toSideResult(): SearchResponse? {
        val href = fixUrl(attr("href")).substringBefore("/ep-")
        val title = selectFirst(".name")?.text()?.trim()?.ifBlank { null } ?: return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = selectFirst("img")?.attr("src")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val html = app.get(
            "$mainUrl/ajax/server/list?servers=${ep.ids}",
            referer = "$mainUrl/",
            headers = xhr
        ).parsedSafe<AjaxResponse>()?.result ?: return false

        val wanted = if (ep.type == "dub") DUB_TYPES else SUB_TYPES
        val servers = Jsoup.parse(html).select(".servers .type")
            .filter { it.attr("data-type").lowercase() in wanted }
            .flatMap { it.select("li[data-link-id]") }

        // Every server usually resolves to the same HLS file; only emit each stream once.
        val seenLinks = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        var found = false
        servers.amap { li ->
            val embed = runCatching {
                app.get(
                    "$mainUrl/ajax/server?get=${li.attr("data-link-id")}",
                    referer = "$mainUrl/",
                    headers = xhr
                ).parsedSafe<ServerResponse>()?.result?.url
            }.getOrNull() ?: return@amap

            // Servers return the same tracks from different CDN hosts; keep one per language.
            val onSub: (SubtitleFile) -> Unit = { sub ->
                if (synchronized(seenSubs) { seenSubs.add(sub.lang) }) subtitleCallback(sub)
            }
            val onLink: (ExtractorLink) -> Unit = { link ->
                if (synchronized(seenLinks) { seenLinks.add(link.url) }) {
                    found = true
                    callback(link)
                }
            }
            runCatching {
                if (embed.contains("megaplay.")) {
                    megaPlay.extract(embed, "$mainUrl/", "${megaPlay.name} ${li.text().trim()}", onSub, onLink)
                } else {
                    loadExtractor(embed, "$mainUrl/", onSub, onLink)
                }
            }
        }
        return found
    }

    private fun getType(t: String?): TvType = when (t?.trim()?.lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova", "ona", "special", "tv special" -> TvType.OVA
        else -> TvType.Anime
    }

    companion object {
        private val SUB_TYPES = setOf("sub", "sub-1", "hsub", "softsub", "raw")
        private val DUB_TYPES = setOf("dub", "dub-1")
    }

    data class EpisodeData(val ids: String, val type: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AjaxResponse(val status: Int? = null, val result: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerResponse(val status: Int? = null, val result: ServerResult? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerResult(val url: String? = null)
}
