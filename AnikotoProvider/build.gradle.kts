// Use an integer for version numbers
version = 2

cloudstream {
    description = "Anime from anikoto.cz. Filter tags in search: #action #2024 #dub #movie #airing #top (see GitHub README for all)"
    authors = listOf("gamernoor24")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    language = "en"

    iconUrl = "https://anikoto.cz/AnikotoTheme/assets/images/favicon.png"
}
