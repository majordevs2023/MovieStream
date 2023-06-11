rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included. Every time you add a new project, you must add it
// to the includes below.

// Plugins are included like this
//val enabled = listOf<String>(
//   "KisskhProvider", "Topdocumentaryfilms", "Tvtwofourseven",
//)

//val disabled = listOf<String>(
//)
//File(rootDir, ".").eachDir { dir ->
//    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
//        include(dir.name)
//    }
//}
//
//fun File.eachDir(block: (File) -> Unit) {
//    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
//}


// To only include a single project, comment out the previous lines (except the first one), and include your plugin like so:
// include("PluginName")

//working dircotry
include(":SuperStream")
include(":VidstreamBundle")
include(":Kissasian")
include(":HDMProvider")
include(":SflixProvider")
include(":BollywoodProvider")
include(":DesicinemasProvider")
include(":Movie123Provider")
include(":CinevezProvider")
include(":SoraStream")
include(":Turkish")
include(":TimefourTv")
include(":Topdocumentaryfilms")

/***
 * working package
 * 1. 2embed
 * 2. AsianLoad,Kissasian(defualt for ausian )
 * 3. HDTODAY
 * 4. sflix.to
 * 5. solarmovies
 * 6. BollywoodProvider,DesicinemasProvider(prefered)
 * 7. Movie123Provider(load mroe not supported)
 * 8. CinevezProvider(indian),
 * 9. SoraStream(best should be defualt),(the folder ontain both the lit and the main)
 * 10.Turkish("my contain two inside")
 * 11 . TimefourTv("main sport channel)
 * 13. Topdocumentaryfilms("main documentry")
 *
 */