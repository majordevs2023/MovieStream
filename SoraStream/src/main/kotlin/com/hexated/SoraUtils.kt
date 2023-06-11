package com.hexated

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraStream.Companion.base64DecodeAPI
import com.hexated.SoraStream.Companion.baymoviesAPI
import com.hexated.SoraStream.Companion.consumetCrunchyrollAPI
import com.hexated.SoraStream.Companion.filmxyAPI
import com.hexated.SoraStream.Companion.gdbot
import com.hexated.SoraStream.Companion.smashyStreamAPI
import com.hexated.SoraStream.Companion.tvMoviesAPI
import com.hexated.SoraStream.Companion.twoEmbedAPI
import com.hexated.SoraStream.Companion.watchOnlineAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getCaptchaToken
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.RequestBodyTypes
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.math.min

val soraAPI = base64DecodeAPI("cA==YXA=cy8=Y20=di8=LnQ=b2s=a2w=bG8=aS4=YXA=ZS0=aWw=b2I=LW0=Z2E=Ly8=czo=dHA=aHQ=")

val soraHeaders = mapOf(
    "lang" to "en",
    "versioncode" to "33",
    "clienttype" to "android_tem3",
    "deviceid" to getDeviceId(),
)

val encodedIndex = arrayOf(
    "GamMovies",
    "JSMovies",
    "BlackMovies",
    "CodexMovies",
    "RinzryMovies",
    "EdithxMovies",
    "XtremeMovies",
    "PapaonMovies[1]",
    "PapaonMovies[2]",
    "JmdkhMovies",
    "RubyMovies",
    "ShinobiMovies",
    "VitoenMovies",
)

val lockedIndex = arrayOf(
    "CodexMovies",
    "EdithxMovies",
)

val mkvIndex = arrayOf(
    "EdithxMovies",
    "JmdkhMovies",
)

val untrimmedIndex = arrayOf(
    "PapaonMovies[1]",
    "PapaonMovies[2]",
    "EdithxMovies",
)

val needRefererIndex = arrayOf(
    "ShinobiMovies",
)

val mimeType = arrayOf(
    "video/x-matroska",
    "video/mp4",
    "video/x-msvideo"
)

data class FilmxyCookies(
    val phpsessid: String? = null,
    val wLog: String? = null,
    val wSec: String? = null,
)

fun String.filterIframe(seasonNum: Int?, lastSeason: Int?, year: Int?, title: String?): Boolean {
    val slug = title.createSlug()
    val dotSlug = slug?.replace("-", ".")
    val spaceSlug = slug?.replace("-", " ")
    return if (seasonNum != null) {
        if (lastSeason == 1) {
            this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)|(\\d{3,4}p)")) && !this.contains(
                "Download",
                true
            )
        } else {
            this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)")) && !this.contains(
                "Download",
                true
            )
        }
    } else {
        this.contains(Regex("(?i)($year)|($dotSlug)|($spaceSlug)")) && !this.contains(
            "Download",
            true
        )
    }
}

fun String.filterMedia(title: String?, yearNum: Int?, seasonNum: Int?): Boolean {
    val fixTitle = title.createSlug()?.replace("-", " ")
    return if (seasonNum != null) {
        when {
            seasonNum > 1 -> this.contains(Regex("(?i)(Season\\s0?1-0?$seasonNum)|(S0?1-S?0?$seasonNum)")) && this.contains(
                Regex("(?i)($fixTitle)|($title)")
            )
            else -> this.contains(Regex("(?i)(Season\\s0?1)|(S0?1)")) && this.contains(
                Regex("(?i)($fixTitle)|($title)")
            ) && this.contains("$yearNum")
        }
    } else {
        this.contains(Regex("(?i)($fixTitle)|($title)")) && this.contains("$yearNum")
    }
}

fun Document.getMirrorLink(): String? {
    return this.select("div.mb-4 a").randomOrNull()
        ?.attr("href")
}

fun Document.getMirrorServer(server: Int): String {
    return this.select("div.text-center a:contains(Server $server)").attr("href")
}

suspend fun extractMirrorUHD(url: String, ref: String): String? {
    var baseDoc = app.get(fixUrl(url, ref)).document
    var downLink = baseDoc.getMirrorLink()
    run lit@{
        (1..2).forEach {
            if (downLink != null) return@lit
            val server = baseDoc.getMirrorServer(it.plus(1))
            baseDoc = app.get(fixUrl(server, ref)).document
            downLink = baseDoc.getMirrorLink()
        }
    }
    return if (downLink?.contains("workers.dev") == true) downLink else base64Decode(
        downLink?.substringAfter(
            "download?url="
        ) ?: return null
    )
}

suspend fun extractBackupUHD(url: String): String? {
    val resumeDoc = app.get(url)

    val script = resumeDoc.document.selectFirst("script:containsData(FormData.)")?.data()

    val ssid = resumeDoc.cookies["PHPSESSID"]
    val baseIframe = getBaseUrl(url)
    val fetchLink =
        script?.substringAfter("fetch('")?.substringBefore("',")?.let { fixUrl(it, baseIframe) }
    val token = script?.substringAfter("'token', '")?.substringBefore("');")

    val body = FormBody.Builder()
        .addEncoded("token", "$token")
        .build()
    val cookies = mapOf("PHPSESSID" to "$ssid")

    val result = app.post(
        fetchLink ?: return null,
        requestBody = body,
        headers = mapOf(
            "Accept" to "*/*",
            "Origin" to baseIframe,
            "Sec-Fetch-Site" to "same-origin"
        ),
        cookies = cookies,
        referer = url
    ).text
    return tryParseJson<UHDBackupUrl>(result)?.url
}

suspend fun extractGdbot(url: String): String? {
    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    )
    val res = app.get(
        "$gdbot/", headers = headers
    )
    val token = res.document.selectFirst("input[name=_token]")?.attr("value")
    val cookiesSet = res.headers.filter { it.first == "set-cookie" }
    val xsrf =
        cookiesSet.find { it.second.contains("XSRF-TOKEN") }?.second?.substringAfter("XSRF-TOKEN=")
            ?.substringBefore(";")
    val session =
        cookiesSet.find { it.second.contains("gdtot_proxy_session") }?.second?.substringAfter("gdtot_proxy_session=")
            ?.substringBefore(";")

    val cookies = mapOf(
        "gdtot_proxy_session" to "$session",
        "XSRF-TOKEN" to "$xsrf"
    )
    val requestFile = app.post(
        "$gdbot/file", data = mapOf(
            "link" to url,
            "_token" to "$token"
        ), headers = headers, referer = "$gdbot/", cookies = cookies
    ).document

    return requestFile.selectFirst("div.mt-8 a.float-right")?.attr("href")
}

suspend fun extractDirectDl(url: String): String? {
    val iframe = app.get(url).document.selectFirst("li.flex.flex-col.py-6 a:contains(Direct DL)")
        ?.attr("href")
    val request = app.get(iframe ?: return null)
    val driveDoc = request.document
    val token = driveDoc.select("section#generate_url").attr("data-token")
    val uid = driveDoc.select("section#generate_url").attr("data-uid")

    val ssid = request.cookies["PHPSESSID"]
    val body =
        """{"type":"DOWNLOAD_GENERATE","payload":{"uid":"$uid","access_token":"$token"}}""".toRequestBody(
            RequestBodyTypes.JSON.toMediaTypeOrNull()
        )

    val json = app.post(
        "https://rajbetmovies.com/action", requestBody = body, headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Cookie" to "PHPSESSID=$ssid",
            "X-Requested-With" to "xmlhttprequest"
        ), referer = request.url
    ).text
    return tryParseJson<DirectDl>(json)?.download_url
}

suspend fun extractDrivebot(url: String): String? {
    val iframeDrivebot =
        app.get(url).document.selectFirst("li.flex.flex-col.py-6 a:contains(Drivebot)")
            ?.attr("href") ?: return null
    return getDrivebotLink(iframeDrivebot)
}

suspend fun extractGdflix(url: String): String? {
    val iframeGdflix =
        app.get(url).document.selectFirst("li.flex.flex-col.py-6 a:contains(GDFlix Direct)")
            ?.attr("href") ?: return null
    val base = getBaseUrl(iframeGdflix)

    val req = app.get(iframeGdflix).document.selectFirst("script:containsData(replace)")?.data()
        ?.substringAfter("replace(\"")
        ?.substringBefore("\")")?.let {
            app.get(fixUrl(it, base))
        } ?: return null

    val iframeDrivebot2 = req.document.selectFirst("a.btn.btn-outline-warning")?.attr("href")
    return getDrivebotLink(iframeDrivebot2)

//    val reqUrl = req.url
//    val ssid = req.cookies["PHPSESSID"]
//    val script = req.document.selectFirst("script:containsData(formData =)")?.data()
//    val key = Regex("append\\(\"key\", \"(\\S+?)\"\\);").find(script ?: return null)?.groupValues?.get(1)
//
//    val body = FormBody.Builder()
//        .addEncoded("action", "direct")
//        .addEncoded("key", "$key")
//        .addEncoded("action_token", "cf_token")
//        .build()
//
//    val gdriveUrl = app.post(
//        reqUrl, requestBody = body,
//        cookies = mapOf("PHPSESSID" to "$ssid"),
//        headers = mapOf(
//            "x-token" to URI(reqUrl).host
//        )
//    ).parsedSafe<Gdflix>()?.url
//
//    return getDirectGdrive(gdriveUrl ?: return null)

}

suspend fun getDrivebotLink(url: String?): String? {
    val driveDoc = app.get(url ?: return null)

    val ssid = driveDoc.cookies["PHPSESSID"]
    val script = driveDoc.document.selectFirst("script:containsData(var formData)")?.data()

    val baseUrl = getBaseUrl(url)
    val token = script?.substringAfter("'token', '")?.substringBefore("');")
    val link =
        script?.substringAfter("fetch('")?.substringBefore("',").let { "$baseUrl$it" }

    val body = FormBody.Builder()
        .addEncoded("token", "$token")
        .build()
    val cookies = mapOf("PHPSESSID" to "$ssid")

    val file = app.post(
        link,
        requestBody = body,
        headers = mapOf(
            "Accept" to "*/*",
            "Origin" to baseUrl,
            "Sec-Fetch-Site" to "same-origin"
        ),
        cookies = cookies,
        referer = url
    ).parsedSafe<DriveBotLink>()?.url ?: return null

    return if (file.startsWith("http")) file else app.get(
        fixUrl(
            file,
            baseUrl
        )
    ).document.selectFirst("script:containsData(window.open)")
        ?.data()?.substringAfter("window.open('")?.substringBefore("')")
}

suspend fun extractOiya(url: String, quality: String): String? {
    val doc = app.get(url).document
    return doc.selectFirst("div.wp-block-button a:matches((?i)$quality)")?.attr("href")
        ?: doc.selectFirst("div.wp-block-button a")?.attr("href")
}

suspend fun extractCovyn(url: String?): Pair<String?, String?>? {
    val request = session.get(url ?: return null, referer = "${tvMoviesAPI}/")
    val filehosting = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
        .find { it.name == "filehosting" }?.value
    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Connection" to "keep-alive",
        "Cookie" to "filehosting=$filehosting",
    )

    val iframe = request.document.findTvMoviesIframe()
    delay(10500)
    val request2 = session.get(
        iframe ?: return null, referer = url, headers = headers
    )

    val iframe2 = request2.document.findTvMoviesIframe()
    delay(10500)
    val request3 = session.get(
        iframe2 ?: return null, referer = iframe, headers = headers
    )

    val response = request3.document
    val videoLink = response.selectFirst("button.btn.btn--primary")?.attr("onclick")
        ?.substringAfter("location = '")?.substringBefore("';")?.let {
            app.get(
                it, referer = iframe2, headers = headers
            ).url
        }
    val size = response.selectFirst("ul.row--list li:contains(Filesize) span:last-child")
        ?.text()

    return Pair(videoLink, size)
}

suspend fun getDirectGdrive(url: String): String {
    val fixUrl = if (url.contains("&export=download")) {
        url
    } else {
        "https://drive.google.com/uc?id=${
            Regex("(?:\\?id=|/d/)(\\S+)/").find("$url/")?.groupValues?.get(1)
        }&export=download"
    }

    val doc = app.get(fixUrl).document
    val form = doc.select("form#download-form").attr("action")
    val uc = doc.select("input#uc-download-link").attr("value")
    return app.post(
        form, data = mapOf(
            "uc-download-link" to uc
        )
    ).url

}

suspend fun invokeSmashyOne(
    name: String,
    url: String,
    callback: (ExtractorLink) -> Unit,
) {
    val script = app.get(url).document.selectFirst("script:containsData(player =)")?.data() ?: return

    val source =
        Regex("file:\\s['\"](\\S+?)['|\"]").find(script)?.groupValues?.get(
            1
        ) ?: return

    source.split(",").map { links ->
        val quality = Regex("\\[(\\d+)]").find(links)?.groupValues?.getOrNull(1)?.trim()
        val link = links.removePrefix("[$quality]").trim()
        callback.invoke(
            ExtractorLink(
                "Smashy [$name]",
                "Smashy [$name]",
                link,
                smashyStreamAPI,
                quality?.toIntOrNull() ?: return@map,
                isM3u8 = link.contains(".m3u8"),
            )
        )
    }

}

suspend fun invokeSmashyTwo(
    name: String,
    url: String,
    callback: (ExtractorLink) -> Unit
) {
    val doc = app.get(url).document
    val script = doc.selectFirst("script:containsData(var secret)")?.data() ?: return
    val secret =
        script.substringAfter("secret = \"").substringBefore("\";").let { base64Decode(it) }
    val key = script.substringAfter("token = \"").substringBefore("\";")
    val source = app.get(
        "$secret$key",
        headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest"
        )
    ).parsedSafe<Smashy1Source>() ?: return

    val videoUrl = base64Decode(source.file ?: return)
    if(videoUrl.contains("/bug")) return
    val quality =
        Regex("(\\d{3,4})[Pp]").find(videoUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P720.value
    callback.invoke(
        ExtractorLink(
            "Smashy [$name]",
            "Smashy [$name]",
            videoUrl,
            "",
            quality,
            videoUrl.contains(".m3u8")
        )
    )
}

suspend fun getSoraIdAndType(title: String?, year: Int?, season: Int?) : Pair<String, String>? {
    val doc = app.get("${base64DecodeAPI("b20=LmM=b2s=a2w=bG8=Ly8=czo=dHA=aHQ=")}/search?keyword=$title").document
    val scriptData = doc.select("div.search-list div.search-video-card").map {
        Triple(
            it.selectFirst("h2.title")?.text().toString(),
            it.selectFirst("div.desc")?.text()
                ?.substringBefore(".")?.toIntOrNull(),
            it.selectFirst("a")?.attr("href")?.split("/")
        )
    }

    val script = if (scriptData.size == 1) {
        scriptData.firstOrNull()
    } else {
        scriptData.find {
            when (season) {
                null -> {
                    it.first.equals(
                        title,
                        true
                    ) && it.second == year
                }
                1 -> {
                    it.first.contains(
                        "$title",
                        true
                    ) && (it.second == year || it.first.contains("Season $season", true))
                }
                else -> {
                    it.first.contains(Regex("(?i)$title\\s?($season|${season.toRomanNumeral()}|Season\\s$season)")) && it.second == year
                }
            }
        }
    }

    val id = script?.third?.last()?.substringBefore("-") ?: return null
    val type = script.third?.get(2)?.let {
        if (it == "drama") "1" else "0"
    } ?: return null

    return id to type
}

suspend fun fetchSoraEpisodes(id: String, type: String, episode: Int?) : EpisodeVo? {
    return app.get(
        "$soraAPI/movieDrama/get?id=${id}&category=${type}",
        headers = soraHeaders
    ).parsedSafe<Load>()?.data?.episodeVo?.find {
        it.seriesNo == (episode ?: 0)
    }
}

suspend fun invokeSapphire(
    url: String? = null,
    isDub: Boolean = false,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    var data = app.get("${url?.replace("player.php", "config.php")}&action=config", referer = url).text
    while (true) {
        if (data.startsWith("{")) break
        if (data == "null") {
            data = app.get("$url&action=config", referer = url).text
            delay(1000)
        }
        data = data.decodeBase64()
    }

    tryParseJson<SapphireSources>(data).let { res ->
        res?.streams?.filter { it.format == "adaptive_hls" && it.hardsub_lang.isNullOrEmpty() }?.reversed()?.map { source ->
            val name = if (isDub) "English Dub" else "Raw"
            M3u8Helper.generateM3u8(
                "Crunchyroll [$name]",
                source.url ?: return@map null,
                "https://static.crunchyroll.com/",
            ).forEach(callback)
        }
        res?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    fixCrunchyrollLang(sub.language ?: return@map null) ?: sub.language,
                    sub.url ?: return@map null
                )
            )
        }
    }
}

suspend fun bypassOuo(url: String?): String? {
    var res = session.get(url ?: return null)
    run lit@{
        (1..2).forEach { _ ->
            if (res.headers["location"] != null) return@lit
            val document = res.document
            val nextUrl = document.select("form").attr("action")
            val data = document.select("form input").mapNotNull {
                it.attr("name") to it.attr("value")
            }.toMap().toMutableMap()
            val captchaKey =
                document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                    .attr("src").substringAfter("render=")
            val token = getCaptchaToken(url, captchaKey)
            data["x-token"] = token ?: ""
            res = session.post(
                nextUrl,
                data = data,
                headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                allowRedirects = false
            )
        }
    }

    return res.headers["location"]
}

suspend fun fetchingKaizoku(
    domain: String,
    postId: String,
    data: List<String>,
    ref: String
): NiceResponse {
    return app.post(
        "$domain/wp-admin/admin-ajax.php",
        data = mapOf(
            "action" to "DDL",
            "post_id" to postId,
            "div_id" to data.first(),
            "tab_id" to data[1],
            "num" to data[2],
            "folder" to data.last()
        ),
        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        referer = ref
    )
}

fun String.splitData(): List<String> {
    return this.substringAfterLast("DDL(").substringBefore(")").split(",")
        .map { it.replace("'", "").trim() }
}

suspend fun bypassFdAds(url: String?): String? {
    val directUrl =
        app.get(url ?: return null, verify = false).document.select("a#link").attr("href")
            .substringAfter("/go/")
            .let { base64Decode(it) }
    val doc = app.get(directUrl, verify = false).document
    val lastDoc = app.post(
        doc.select("form#landing").attr("action"),
        data = mapOf("go" to doc.select("form#landing input").attr("value")),
        verify = false
    ).document
    val json = lastDoc.select("form#landing input[name=newwpsafelink]").attr("value")
        .let { base64Decode(it) }
    val finalJson =
        tryParseJson<FDAds>(json)?.linkr?.substringAfter("redirect=")?.let { base64Decode(it) }
    return tryParseJson<Safelink>(finalJson)?.safelink
}

suspend fun bypassHrefli(url: String): String? {
    val postUrl = url.substringBefore("?id=").substringAfter("/?")
    val res = app.post(
        postUrl, data = mapOf(
            "_wp_http" to url.substringAfter("?id=")
        )
    ).document

    val link = res.select("form#landing").attr("action")
    val wpHttp = res.select("input[name=_wp_http2]").attr("value")
    val token = res.select("input[name=token]").attr("value")

    val blogRes = app.post(
        link, data = mapOf(
            "_wp_http2" to wpHttp,
            "token" to token
        )
    ).text

    val skToken = blogRes.substringAfter("?go=").substringBefore("\"")
    val driveUrl = app.get(
        "$postUrl?go=$skToken", cookies = mapOf(
            skToken to wpHttp
        )
    ).document.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=")
    val path = app.get(driveUrl ?: return null).text.substringAfter("replace(\"")
        .substringBefore("\")")
    if (path == "/404") return null
    return fixUrl(path, getBaseUrl(driveUrl))
}

suspend fun getTvMoviesServer(url: String, season: Int?, episode: Int?): Pair<String, String?>? {

    val req = app.get(url)
    if (!req.isSuccessful) return null
    val doc = req.document

    return if (season == null) {
        doc.select("table.wp-block-table tr:last-child td:first-child").text() to
                doc.selectFirst("table.wp-block-table tr a")?.attr("href").let { link ->
                    app.get(link ?: return null).document.select("div#text-url a")
                        .mapIndexed { index, element ->
                            element.attr("href") to element.parent()?.textNodes()?.getOrNull(index)
                                ?.text()
                        }.filter { it.second?.contains("Subtitles", true) == false }
                        .map { it.first }
                }.lastOrNull()
    } else {
        doc.select("div.vc_tta-panels div#Season-$season table.wp-block-table tr:last-child td:first-child")
            .text() to
                doc.select("div.vc_tta-panels div#Season-$season table.wp-block-table tr a")
                    .mapNotNull { ele ->
                        app.get(ele.attr("href")).document.select("div#text-url a")
                            .mapIndexed { index, element ->
                                element.attr("href") to element.parent()?.textNodes()
                                    ?.getOrNull(index)?.text()
                            }.find { it.second?.contains("Episode $episode", true) == true }?.first
                    }.lastOrNull()
    }
}

suspend fun getFilmxyCookies(imdbId: String? = null, season: Int? = null): FilmxyCookies? {

    val url = if (season == null) {
        "${filmxyAPI}/movie/$imdbId"
    } else {
        "${filmxyAPI}/tv/$imdbId"
    }
    val cookieUrl = "${filmxyAPI}/wp-admin/admin-ajax.php"

    val res = session.get(
        url,
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        ),
    )

    if (!res.isSuccessful) return FilmxyCookies()

    val userNonce =
        res.document.select("script").find { it.data().contains("var userNonce") }?.data()?.let {
            Regex("var\\suserNonce.*?\"(\\S+?)\";").find(it)?.groupValues?.get(1)
        }

    var phpsessid = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
        .first { it.name == "PHPSESSID" }.value

    session.post(
        cookieUrl,
        data = mapOf(
            "action" to "guest_login",
            "nonce" to "$userNonce",
        ),
        headers = mapOf(
            "Cookie" to "PHPSESSID=$phpsessid; G_ENABLED_IDPS=google",
            "X-Requested-With" to "XMLHttpRequest",
        )
    )

    val cookieJar = session.baseClient.cookieJar.loadForRequest(cookieUrl.toHttpUrl())
    phpsessid = cookieJar.first { it.name == "PHPSESSID" }.value
    val wLog =
        cookieJar.first { it.name == "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" }.value
    val wSec = cookieJar.first { it.name == "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" }.value

    return FilmxyCookies(phpsessid, wLog, wSec)
}

fun Document.findTvMoviesIframe(): String? {
    return this.selectFirst("script:containsData(var seconds)")?.data()?.substringAfter("href='")
        ?.substringBefore("'>")
}

suspend fun searchWatchOnline(
    title: String? = null,
    season: Int? = null,
    imdbId: String? = null,
    tmdbId: Int? = null,
): NiceResponse? {
    val wTitle = title?.dropLast(1) // weird but this will make search working
    val mediaId = app.get(
        if (season == null) {
            "${watchOnlineAPI}/api/v1/movies?filters[q]=$wTitle"
        } else {
            "${watchOnlineAPI}/api/v1/shows?filters[q]=$wTitle"
        }
    ).parsedSafe<WatchOnlineSearch>()?.items?.find {
        it.imdb_id == imdbId || it.tmdb_id == tmdbId || it.imdb_id == imdbId?.removePrefix("tt")
    }?.slug

    return app.get(
        fixUrl(
            mediaId ?: return null, if (season == null) {
                "${watchOnlineAPI}/movies/view"
            } else {
                "${watchOnlineAPI}/shows/view"
            }
        )
    )
}

suspend fun searchCrunchyrollAnimeId(title: String): String? {
    val res = app.get("${consumetCrunchyrollAPI}/search/$title",timeout = 600L)
        .parsedSafe<ConsumetSearchResponse>()?.results
    return (if (res?.size == 1) {
        res.firstOrNull()
    } else {
        res?.find {
            (it.title?.contains(
                title,
                true
            ) == true || it.title.createSlug()
                ?.contains("${title.createSlug()}", true) == true) && it.type.equals("series")
        }
    })?.id
}

fun CrunchyrollDetails.findCrunchyrollId(
    season: Int?,
    episode: Int?,
    epsTitle: String?
): List<Pair<CrunchyrollEpisodes?, String?>?> {
    val sub = this.episodes?.filterKeys { it.contains("subbed") }.matchingEpisode(epsTitle, season, episode) to "Raw"
    val dub = this.episodes?.filterKeys { it.contains("English Dub") }.matchingEpisode(epsTitle, season, episode) to "English Dub"
    return listOf(sub, dub)
}

fun Map<String, List<CrunchyrollEpisodes>>?.matchingEpisode(
    epsTitle: String?,
    season: Int?,
    episode: Int?
): CrunchyrollEpisodes? {
    return this?.mapNotNull { eps ->
        eps.value.find {
            (it.episode_number == episode && it.season_number == season) || it.title.equals(
                epsTitle,
                true
            )
        } ?: eps.value.find { it.episode_number == episode }
    }?.firstOrNull()
}

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
    }
}

fun getTitleSlug(title: String? = null): Pair<String?, String?> {
    val slug = title.createSlug()
    return slug?.replace("-", "\\W") to title?.replace(" ", "_")
}

fun getIndexQuery(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null
): String {
    val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
    return (if (season == null) {
        "$title ${year ?: ""}"
    } else {
        "$title S${seasonSlug}E${episodeSlug}"
    }).trim()
}

fun searchIndex(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    year: Int? = null,
    response: String,
    isTrimmed: Boolean = true,
): List<IndexMedia>? {
    val files = tryParseJson<IndexSearch>(response)?.data?.files?.filter { media ->
        matchingIndex(
            media.name ?: return null,
            media.mimeType ?: return null,
            title ?: return null,
            year,
            season,
            episode
        )
    }?.distinctBy { it.name }?.sortedByDescending { it.size?.toLongOrNull() ?: 0 } ?: return null

    return if (isTrimmed) {
        files.let { file ->
            listOfNotNull(
                file.find { it.name?.contains("2160p", true) == true },
                file.find { it.name?.contains("1080p", true) == true }
            )
        }
    } else {
        files
    }
}

fun matchingIndex(
    mediaName: String?,
    mediaMimeType: String?,
    title: String?,
    year: Int?,
    season: Int?,
    episode: Int?,
    include720: Boolean = false
): Boolean {
    val (wSlug, dwSlug) = getTitleSlug(title)
    val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
    return (if (season == null) {
        mediaName?.contains(Regex("(?i)(?:$wSlug|$dwSlug).*$year")) == true
    } else {
        mediaName?.contains(Regex("(?i)(?:$wSlug|$dwSlug).*S${seasonSlug}.?E${episodeSlug}")) == true
    }) && mediaName?.contains(
        if (include720) Regex("(?i)(2160p|1080p|720p)") else Regex("(?i)(2160p|1080p)")
    ) == true && ((mediaMimeType in mimeType) || mediaName.contains(Regex("\\.mkv|\\.mp4|\\.avi")))
}

suspend fun getConfig(): BaymoviesConfig {
    val regex = """const country = "(.*?)";
const downloadtime = "(.*?)";
var arrayofworkers = (.*)""".toRegex()
    val js = app.get(
        "https://geolocation.zindex.eu.org/api.js",
        referer = "$baymoviesAPI/",
    ).text
    val match = regex.find(js) ?: throw ErrorLoadingException()
    val country = match.groupValues[1]
    val downloadTime = match.groupValues[2]
    val workers = tryParseJson<List<String>>(match.groupValues[3])
        ?: throw ErrorLoadingException()

    return BaymoviesConfig(country, downloadTime, workers)
}

fun decodeIndexJson(json: String): String {
    val slug = json.reversed().substring(24)
    return base64Decode(slug.substring(0, slug.length - 20))
}

fun String.decryptGomoviesJson(key: String = "123"): String {
    val sb = StringBuilder()
    var i = 0
    while (i < this.length) {
        var j = 0
        while (j < key.length && i < this.length) {
            sb.append((this[i].code xor key[j].code).toChar())
            j++
            i++
        }
    }
    return sb.toString()
}

fun Headers.getGomoviesCookies(cookieKey: String = "set-cookie"): Map<String, String> {
    val cookieList =
        this.filter { it.first.equals(cookieKey, ignoreCase = true) }.mapNotNull {
            it.second.split(";").firstOrNull()
        }
    return cookieList.associate {
        val split = it.split("=", limit = 2)
        (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
    }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
}

fun String?.createSlug(): String? {
    return this?.replace(Regex("[^\\w\\s-]"), "")
        ?.replace(" ", "-")
        ?.replace(Regex("( – )|( -)|(- )|(--)"), "-")
        ?.lowercase()
}

fun getLanguage(str: String): String {
    return if (str.contains("(in_ID)")) "Indonesian" else str
}

fun bytesToGigaBytes(number: Double): Double = number / 1024000000

fun getKisskhTitle(str: String?): String? {
    return str?.replace(Regex("[^a-zA-Z\\d]"), "-")
}

fun String.getFileSize() : Float? {
    val size = Regex("(\\d+\\.?\\d+\\sGB|MB)").find(this)?.groupValues?.get(0)?.trim()
    val num = Regex("(\\d+\\.?\\d+)").find(size ?: return null)?.groupValues?.get(0)?.toFloat() ?: return null
    return when {
        size.contains("GB") -> num * 1000000
        else -> num * 1000
    }
}

fun getIndexQualityTags(str: String?): String {
    return Regex("\\d{3,4}[pP]\\.?(.*?)\\.(mkv|mp4|avi)").find(str ?: "")?.groupValues?.getOrNull(1)
        ?.replace(".", " ")?.trim() ?: ""
}

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getQuality(str: String): Int {
    return when (str) {
        "360p" -> Qualities.P240.value
        "480p" -> Qualities.P360.value
        "720p" -> Qualities.P480.value
        "1080p" -> Qualities.P720.value
        "1080p Ultra" -> Qualities.P1080.value
        else -> getQualityFromName(str)
    }
}

fun getGMoviesQuality(str: String): Int {
    return when {
        str.contains("480P", true) -> Qualities.P480.value
        str.contains("720P", true) -> Qualities.P720.value
        str.contains("1080P", true) -> Qualities.P1080.value
        str.contains("4K", true) -> Qualities.P2160.value
        else -> Qualities.Unknown.value
    }
}

fun getSoraQuality(quality: String): Int {
    return when (quality) {
        "GROOT_FD" -> Qualities.P360.value
        "GROOT_LD" -> Qualities.P480.value
        "GROOT_SD" -> Qualities.P720.value
        "GROOT_HD" -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }
}

fun getFDoviesQuality(str: String): String {
    return when {
        str.contains("1080P", true) -> "1080P"
        str.contains("4K", true) -> "4K"
        else -> ""
    }
}

fun getVipLanguage(str: String): String {
    return when (str) {
        "in_ID" -> "Indonesian"
        "pt" -> "Portuguese"
        else -> str.split("_").first().let {
            SubtitleHelper.fromTwoLettersToLanguage(it).toString()
        }
    }
}

fun getDbgoLanguage(str: String): String {
    return when (str) {
        "Русский" -> "Russian"
        "Українська" -> "Ukrainian"
        else -> str
    }
}

fun fixCrunchyrollLang(language: String?): String? {
    return SubtitleHelper.fromTwoLettersToLanguage(language ?: return null)
        ?: SubtitleHelper.fromTwoLettersToLanguage(language.substringBefore("-"))
}

fun getDeviceId(length: Int = 16): String {
    val allowedChars = ('a'..'f') + ('0'..'9')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

fun String.encodeUrl(): String {
    val url = URL(this)
    val uri = URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref)
    return uri.toURL().toString()
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun String.decodeBase64(): String {
    return Base64.decode(this, Base64.DEFAULT).toString(Charsets.UTF_8)
}

fun encode(input: String): String? = URLEncoder.encode(input, "utf-8")

fun decryptStreamUrl(data: String): String {

    fun getTrash(arr: List<String>, item: Int): List<String> {
        val trash = ArrayList<List<String>>()
        for (i in 1..item) {
            trash.add(arr)
        }
        return trash.reduce { acc, list ->
            val temp = ArrayList<String>()
            acc.forEach { ac ->
                list.forEach { li ->
                    temp.add(ac.plus(li))
                }
            }
            return@reduce temp
        }
    }

    val trashList = listOf("@", "#", "!", "^", "$")
    val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
    var trashString = data.replace("#2", "").split("//_//").joinToString("")

    trashSet.forEach {
        val temp = base64Encode(it.toByteArray())
        trashString = trashString.replace(temp, "")
    }

    return base64Decode(trashString)

}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

suspend fun loadLinksWithWebView(
    url: String,
    callback: (ExtractorLink) -> Unit
) {
    val foundVideo = WebViewResolver(
        Regex("""\.m3u8|i7njdjvszykaieynzsogaysdgb0hm8u1mzubmush4maopa4wde\.com""")
    ).resolveUsingWebView(
        requestCreator(
            "GET", url, referer = "https://olgply.com/"
        )
    ).first ?: return

    callback.invoke(
        ExtractorLink(
            "Olgply",
            "Olgply",
            foundVideo.url.toString(),
            "",
            Qualities.P1080.value,
            true
        )
    )
}

fun Int.toRomanNumeral(): String = Symbol.closestBelow(this)
    .let { symbol ->
        if (symbol != null) {
            "$symbol${(this - symbol.decimalValue).toRomanNumeral()}"
        } else {
            ""
        }
    }

private enum class Symbol(val decimalValue: Int) {
    I(1),
    IV(4),
    V(5),
    IX(9),
    X(10);

    companion object {
        fun closestBelow(value: Int) =
            values()
                .sortedByDescending { it.decimalValue }
                .firstOrNull { value >= it.decimalValue }
    }
}

// code found on https://stackoverflow.com/a/63701411

/**
 * Conforming with CryptoJS AES method
 */
// see https://gist.github.com/thackerronak/554c985c3001b16810af5fc0eb5c358f
@Suppress("unused", "FunctionName", "SameParameterValue")
object CryptoAES {

    private const val KEY_SIZE = 256
    private const val IV_SIZE = 128
    private const val HASH_CIPHER = "AES/CBC/PKCS5Padding"
    private const val AES = "AES"
    private const val KDF_DIGEST = "MD5"

    // Seriously crypto-js, what's wrong with you?
    private const val APPEND = "Salted__"

    /**
     * Encrypt
     * @param password passphrase
     * @param plainText plain string
     */
    fun encrypt(password: String, plainText: String): String {
        val saltBytes = generateSalt(8)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        EvpKDF(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, iv)
        val keyS = SecretKeySpec(key, AES)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keyS, ivSpec)
        val cipherText = cipher.doFinal(plainText.toByteArray())
        // Thanks kientux for this: https://gist.github.com/kientux/bb48259c6f2133e628ad
        // Create CryptoJS-like encrypted!
        val sBytes = APPEND.toByteArray()
        val b = ByteArray(sBytes.size + saltBytes.size + cipherText.size)
        System.arraycopy(sBytes, 0, b, 0, sBytes.size)
        System.arraycopy(saltBytes, 0, b, sBytes.size, saltBytes.size)
        System.arraycopy(cipherText, 0, b, sBytes.size + saltBytes.size, cipherText.size)
        val bEncode = Base64.encode(b, Base64.NO_WRAP)
        return String(bEncode)
    }

    /**
     * Decrypt
     * Thanks Artjom B. for this: http://stackoverflow.com/a/29152379/4405051
     * @param password passphrase
     * @param cipherText encrypted string
     */
    fun decrypt(password: String, cipherText: String): String {
        val ctBytes = Base64.decode(cipherText.toByteArray(), Base64.NO_WRAP)
        val saltBytes = Arrays.copyOfRange(ctBytes, 8, 16)
        val cipherTextBytes = Arrays.copyOfRange(ctBytes, 16, ctBytes.size)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        EvpKDF(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, iv)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val keyS = SecretKeySpec(key, AES)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))
        val plainText = cipher.doFinal(cipherTextBytes)
        return String(plainText)
    }

    private fun EvpKDF(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        resultKey: ByteArray,
        resultIv: ByteArray
    ): ByteArray {
        return EvpKDF(password, keySize, ivSize, salt, 1, KDF_DIGEST, resultKey, resultIv)
    }

    @Suppress("NAME_SHADOWING")
    private fun EvpKDF(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        iterations: Int,
        hashAlgorithm: String,
        resultKey: ByteArray,
        resultIv: ByteArray
    ): ByteArray {
        val keySize = keySize / 32
        val ivSize = ivSize / 32
        val targetKeySize = keySize + ivSize
        val derivedBytes = ByteArray(targetKeySize * 4)
        var numberOfDerivedWords = 0
        var block: ByteArray? = null
        val hash = MessageDigest.getInstance(hashAlgorithm)
        while (numberOfDerivedWords < targetKeySize) {
            if (block != null) {
                hash.update(block)
            }
            hash.update(password)
            block = hash.digest(salt)
            hash.reset()
            // Iterations
            for (i in 1 until iterations) {
                block = hash.digest(block!!)
                hash.reset()
            }
            System.arraycopy(
                block!!, 0, derivedBytes, numberOfDerivedWords * 4,
                min(block.size, (targetKeySize - numberOfDerivedWords) * 4)
            )
            numberOfDerivedWords += block.size / 4
        }
        System.arraycopy(derivedBytes, 0, resultKey, 0, keySize * 4)
        System.arraycopy(derivedBytes, keySize * 4, resultIv, 0, ivSize * 4)
        return derivedBytes // key + iv
    }

    private fun generateSalt(length: Int): ByteArray {
        return ByteArray(length).apply {
            SecureRandom().nextBytes(this)
        }
    }
}

object RabbitStream {

    suspend fun MainAPI.extractRabbitStream(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        useSidAuthentication: Boolean,
        /** Used for extractorLink name, input: Source name */
        extractorData: String? = null,
        decryptKey: String? = null,
        nameTransformer: (String) -> String,
    ) = suspendSafeApiCall {
        // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> https://rapid-cloud.ru/embed-6
        val mainIframeUrl =
            url.substringBeforeLast("/")
        val mainIframeId = url.substringAfterLast("/")
            .substringBefore("?") // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> dcPOVRE57YOT
        var sid: String? = null
        if (useSidAuthentication && extractorData != null) {
            negotiateNewSid(extractorData)?.also { pollingData ->
                app.post(
                    "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                    requestBody = "40".toRequestBody(),
                    timeout = 60
                )
                val text = app.get(
                    "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                    timeout = 60
                ).text.replaceBefore("{", "")

                sid = AppUtils.parseJson<PollingData>(text).sid
                ioSafe { app.get("$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}") }
            }
        }
        val getSourcesUrl = "${
            mainIframeUrl.replace(
                "/embed",
                "/ajax/embed"
            )
        }/getSources?id=$mainIframeId${sid?.let { "$&sId=$it" } ?: ""}"
        val response = app.get(
            getSourcesUrl,
            referer = mainUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive",
                "TE" to "trailers"
            )
        )

        val sourceObject = if (decryptKey != null) {
            val encryptedMap = response.parsedSafe<SourceObjectEncrypted>()
            val sources = encryptedMap?.sources
            if (sources == null || encryptedMap.encrypted == false) {
                response.parsedSafe()
            } else {
                val decrypted =
                    decryptMapped<List<Sources>>(sources, decryptKey)
                SourceObject(
                    sources = decrypted,
                    tracks = encryptedMap.tracks
                )
            }
        } else {
            response.parsedSafe()
        } ?: return@suspendSafeApiCall

        sourceObject.tracks?.forEach { track ->
            track?.toSubtitleFile()?.let { subtitleFile ->
                subtitleCallback.invoke(subtitleFile)
            }
        }

        val list = listOf(
            sourceObject.sources to "source 1",
            sourceObject.sources1 to "source 2",
            sourceObject.sources2 to "source 3",
            sourceObject.sourcesBackup to "source backup"
        )

        list.forEach { subList ->
            subList.first?.forEach { source ->
                source?.toExtractorLink(
                    "Vidcloud",
                    "$twoEmbedAPI/",
                    extractorData,
                )
                    ?.forEach {
                        // Sets Zoro SID used for video loading
//                            (this as? ZoroProvider)?.sid?.set(it.url.hashCode(), sid)
                        callback(it)
                    }
            }
        }
    }

    private suspend fun Sources.toExtractorLink(
        name: String,
        referer: String,
        extractorData: String? = null,
    ): List<ExtractorLink>? {
        return this.file?.let { file ->
            //println("FILE::: $file")
            val isM3u8 = URI(this.file).path.endsWith(".m3u8") || this.type.equals(
                "hls",
                ignoreCase = true
            )
            return if (isM3u8) {
                suspendSafeApiCall {
                    M3u8Helper().m3u8Generation(
                        M3u8Helper.M3u8Stream(
                            this.file,
                            null,
                            mapOf("Referer" to "https://mzzcloud.life/")
                        ), false
                    )
                        .map { stream ->
                            ExtractorLink(
                                name,
                                name,
                                stream.streamUrl,
                                referer,
                                getQualityFromName(stream.quality?.toString()),
                                true,
                                extractorData = extractorData
                            )
                        }
                }.takeIf { !it.isNullOrEmpty() } ?: listOf(
                    // Fallback if m3u8 extractor fails
                    ExtractorLink(
                        name,
                        name,
                        this.file,
                        referer,
                        getQualityFromName(this.label),
                        isM3u8,
                        extractorData = extractorData
                    )
                )
            } else {
                listOf(
                    ExtractorLink(
                        name,
                        name,
                        file,
                        referer,
                        getQualityFromName(this.label),
                        false,
                        extractorData = extractorData
                    )
                )
            }
        }
    }

    private fun Tracks.toSubtitleFile(): SubtitleFile? {
        return this.file?.let {
            SubtitleFile(
                this.label ?: "Unknown",
                it
            )
        }
    }

    /**
     * Generates a session
     * 1 Get request.
     * */
    private suspend fun negotiateNewSid(baseUrl: String): PollingData? {
        // Tries multiple times
        for (i in 1..5) {
            val jsonText =
                app.get("$baseUrl&t=${generateTimeStamp()}").text.replaceBefore(
                    "{",
                    ""
                )
//            println("Negotiated sid $jsonText")
            AppUtils.parseJson<PollingData?>(jsonText)?.let { return it }
            delay(1000L * i)
        }
        return null
    }

    private fun generateTimeStamp(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        var code = ""
        var time = APIHolder.unixTimeMS
        while (time > 0) {
            code += chars[(time % (chars.length)).toInt()]
            time /= chars.length
        }
        return code.reversed()
    }

    suspend fun getKey(): String? {
        return app.get("https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt")
            .text
    }

    private inline fun <reified T> decryptMapped(input: String, key: String): T? {
        return tryParseJson(decrypt(input, key))
    }

    private fun decrypt(input: String, key: String): String {
        return decryptSourceUrl(
            generateKey(
                base64DecodeArray(input).copyOfRange(8, 16),
                key.toByteArray()
            ), input
        )
    }

    private fun generateKey(salt: ByteArray, secret: ByteArray): ByteArray {
        var key = md5(secret + salt)
        var currentKey = key
        while (currentKey.size < 48) {
            key = md5(key + secret + salt)
            currentKey += key
        }
        return currentKey
    }

    private fun md5(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(input)
    }

    private fun decryptSourceUrl(decryptionKey: ByteArray, sourceUrl: String): String {
        val cipherData = base64DecodeArray(sourceUrl)
        val encrypted = cipherData.copyOfRange(16, cipherData.size)
        val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")

        Objects.requireNonNull(aesCBC).init(
            Cipher.DECRYPT_MODE, SecretKeySpec(
                decryptionKey.copyOfRange(0, 32),
                "AES"
            ),
            IvParameterSpec(decryptionKey.copyOfRange(32, decryptionKey.size))
        )
        val decryptedData = aesCBC!!.doFinal(encrypted)
        return String(decryptedData, StandardCharsets.UTF_8)
    }

    data class PollingData(
        @JsonProperty("sid") val sid: String? = null,
        @JsonProperty("upgrades") val upgrades: ArrayList<String> = arrayListOf(),
        @JsonProperty("pingInterval") val pingInterval: Int? = null,
        @JsonProperty("pingTimeout") val pingTimeout: Int? = null
    )

    data class Tracks(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )

    data class SourceObject(
        @JsonProperty("sources") val sources: List<Sources?>? = null,
        @JsonProperty("sources_1") val sources1: List<Sources?>? = null,
        @JsonProperty("sources_2") val sources2: List<Sources?>? = null,
        @JsonProperty("sourcesBackup") val sourcesBackup: List<Sources?>? = null,
        @JsonProperty("tracks") val tracks: List<Tracks?>? = null
    )

    data class SourceObjectEncrypted(
        @JsonProperty("sources") val sources: String?,
        @JsonProperty("encrypted") val encrypted: Boolean?,
        @JsonProperty("sources_1") val sources1: String?,
        @JsonProperty("sources_2") val sources2: String?,
        @JsonProperty("sourcesBackup") val sourcesBackup: String?,
        @JsonProperty("tracks") val tracks: List<Tracks?>?
    )

}