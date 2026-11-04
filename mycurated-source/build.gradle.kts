import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "My Curated"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "My Curated"
        lang = "all"
        // Not a site root: this is the curated manifest, editable in the source's settings.
        baseUrl {
            custom("https://raw.githubusercontent.com/eitandub22/MyMangaExtension/main/manga.json")
        }
    }
}
