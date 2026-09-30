import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CorujaToon"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://corujatoon.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/series/..*")
    }
}
