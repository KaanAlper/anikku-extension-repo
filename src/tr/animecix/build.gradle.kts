import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeciX"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "tr"
        baseUrl = "https://animecix.tv"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
