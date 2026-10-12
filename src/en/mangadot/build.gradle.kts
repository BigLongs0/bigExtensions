import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaDot"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://mangadot.net"
        lang = "en"
    }

    deeplink {
        path("/manga/..*")
    }
}

dependencies {
    implementation(project(":lib:webviewfetch"))
}
