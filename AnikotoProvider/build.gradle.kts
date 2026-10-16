// Use an integer for version numbers
version = 1

cloudstream {
    description = "Anime from anikoto.cz (sub & dub, HLS via MegaPlay)"
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
