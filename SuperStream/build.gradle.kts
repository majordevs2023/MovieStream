// use an integer for version numbers
version = 8


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    authors = listOf("Blatzar")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "Movie",
        "AnimeMovie",
        "TvSeries",
    )
    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions/master/SuperStream/icon.png"
}
