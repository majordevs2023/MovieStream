package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError

class AmharicMovieProvider : MainAPI() {

    override var mainUrl = "https://www.fanotube.net/category.php?cat=ethiopian-amharic-movies"
    override var name = "fanotube"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie
    )


    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
//        val items = ArrayList<HomePageList>()
//        val soup = app.get(mainUrl).document
//        val videoItems = soup .select("li.col-xs-6")
//        try {
//            val elements: List<SearchResponse> = videoItems.map {
//                val titleElement = it.selectFirst("h3 a")
//                val title = titleElement?.text()
//                val href = titleElement?.attr("href")
//                val thumbnailElement = it.selectFirst("img")
//                val thumbnailUrl = thumbnailElement?.attr("data-echo")
//
//
//        }catch (e:Exception){
//            logError(e)
//        }
//
//    }

    }

}