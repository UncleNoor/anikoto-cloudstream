package com.anikoto

import java.net.URLEncoder

/**
 * Turns a search query with `#tags` into anikoto's /filter URL parameters.
 *
 *   "#action #2024 #dub"        -> genre Action, year 2024, dubbed only
 *   "slime #isekai #top"        -> keyword "slime", genre Isekai, sorted by score
 *   "#romance #movie #winter"   -> genre Romance, type Movie, winter season
 *
 * Words without `#` are the normal keyword. Unknown tags are ignored.
 */
object SearchFilters {
    private val seasons = mapOf(
        "fall" to "fall", "autumn" to "fall", "summer" to "summer",
        "spring" to "spring", "winter" to "winter",
    )
    private val languages = mapOf("sub" to "sub", "subbed" to "sub", "dub" to "dub", "dubbed" to "dub")
    private val statuses = mapOf(
        "airing" to "currently-airing", "ongoing" to "currently-airing",
        "finished" to "finished-airing", "completed" to "finished-airing",
        "upcoming" to "not-yet-aired", "notaired" to "not-yet-aired",
    )
    private val types = mapOf(
        "tv" to "TV", "movie" to "Movie", "movies" to "Movie", "ova" to "OVA", "ona" to "ONA",
        "special" to "Special", "tvspecial" to "TV Special", "tvshort" to "TV_SHORT", "short" to "TV_SHORT",
        "music" to "Music",
    )
    private val ratings = mapOf(
        "g" to "G", "pg" to "PG", "pg13" to "PG-13", "r" to "R", "r17" to "R", "rplus" to "R+", "r+" to "R+", "rx" to "Rx",
    )
    private val sorts = mapOf(
        "top" to "score", "best" to "score", "score" to "score", "rated" to "score",
        "popular" to "most-viewed", "views" to "most-viewed", "mostviewed" to "most-viewed",
        "latest" to "latest-updated", "updated" to "latest-updated",
        "new" to "latest-added", "added" to "latest-added", "recent" to "latest-added",
        "az" to "name-az", "a-z" to "name-az", "name" to "name-az",
        "release" to "release-date", "released" to "release-date",
        "episodes" to "number_of_episodes",
    )

    fun hasTags(query: String) = query.split(Regex("\\s+")).any { it.length > 1 && it.startsWith("#") }

    /** Key used to match genre names: lowercase letters/digits only ("Slice of Life" -> "sliceoflife"). */
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9+]"), "")

    /**
     * @param genres normalized genre name -> anikoto genre id
     * @return the query string (without leading '?') for /filter
     */
    fun buildQuery(query: String, genres: Map<String, String>, page: Int): String {
        val words = mutableListOf<String>()
        val params = mutableListOf<Pair<String, String>>()
        var sort: String? = null

        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            if (!word.startsWith("#") || word.length < 2) {
                words += word
                return@forEach
            }
            val raw = word.drop(1).lowercase()
            val tag = norm(raw)
            when {
                raw.matches(Regex("(19|20)\\d\\d")) -> params += "year[]" to raw
                genres.containsKey(tag) -> params += "genre[]" to genres.getValue(tag)
                seasons.containsKey(tag) -> params += "season[]" to seasons.getValue(tag)
                languages.containsKey(tag) -> params += "language[]" to languages.getValue(tag)
                statuses.containsKey(tag) -> params += "status[]" to statuses.getValue(tag)
                types.containsKey(tag) -> params += "term_type[]" to types.getValue(tag)
                ratings.containsKey(raw) -> params += "rating[]" to ratings.getValue(raw)
                ratings.containsKey(tag) -> params += "rating[]" to ratings.getValue(tag)
                sorts.containsKey(raw) -> sort = sorts.getValue(raw)
                sorts.containsKey(tag) -> sort = sorts.getValue(tag)
            }
        }

        val all = buildList {
            add("keyword" to words.joinToString(" "))
            addAll(params.distinct())
            sort?.let { add("sort" to it) }
            add("page" to page.toString())
        }
        return all.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
