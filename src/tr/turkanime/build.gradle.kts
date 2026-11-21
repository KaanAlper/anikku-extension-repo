import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TürkAnime"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://www.turkanime.co"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:googledriveextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:pixeldrainextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:sendvidextractor"))
    implementation(project(":lib:sibnetextractor"))
    implementation(project(":lib:vidmolyextractor"))
    implementation(project(":lib:vkextractor"))
}
