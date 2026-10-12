import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kagane"
    versionCode = 32
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://kagane.to"
    }

    deeplink {
        path("/series/..*")
    }
}

dependencies {
    implementation(project(":lib:webviewfetch"))
}
