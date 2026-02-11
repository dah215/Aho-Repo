// use an integer for version numbers
version = 5


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "(Mexican) Anime"
    language    = "mx"
    authors = listOf("Aho-Repo")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf("Movie","Anime","AnimeMovie")
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRBY6zyssWF7b3GT1v3Q1-_kwxYqmKm-YjzcyeuHldsHg&s"

    isCrossPlatform = false
}
