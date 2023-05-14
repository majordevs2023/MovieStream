// use an integer for version numbers
version = 13


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Due to the video host changing encryption methods extremely often these extensions might not work perfectly. Also includes Dopebox, Solarmovie, Zoro, HDToday and 2embed"
    // authors = listOf("Cloudburst")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime",
        "AnimeMovie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.2embed.to&sz=%size%"
}
