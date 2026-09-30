import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaLivre.blog"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://mangalivre.blog"
    }
}
