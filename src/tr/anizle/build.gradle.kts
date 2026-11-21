import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anizle"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://anizle.co"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
