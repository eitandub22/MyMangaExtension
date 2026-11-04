package eu.kanade.tachiyomi.extension.all.mycurated

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A personal, curated source. [baseUrl] is a JSON manifest rather than a site root, and each
 * entry carries the absolute URL of that series' own site.
 *
 * Two site shapes are handled, both WordPress:
 *
 *  - **REST**, for a site that is one series and exposes `/wp-json/wp/v2/`. The manifest entry
 *    is the site root, because the whole post feed is the series.
 *  - **Madara**, the manga theme, which keeps its content out of the REST API entirely. The
 *    manifest entry is a series URL, because one host carries thousands of series.
 *
 * Adding a manga on a host of either shape needs only a manifest edit, no rebuild.
 */
@Source
abstract class MyCurated : KeiSource() {

    // headersBuilder() is final and sets Referer/Origin from baseUrl -- but baseUrl here is the
    // manifest, not the site being scraped. Some hosts (manhuatop's image CDN) hotlink-protect
    // on Referer and 403 a value that points at raw.githubusercontent.com, so every request gets
    // rewritten to point at its own target instead.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        val origin = "${request.url.scheme}://${request.url.host}"
        chain.proceed(request.newBuilder().header("Referer", "$origin/").header("Origin", origin).build())
    }

    private suspend fun manifest(): List<Entry> = client.get(baseUrl, headers, CacheControl.FORCE_NETWORK).parseAs<List<Entry>>()

    private fun Entry.toSManga() = SManga.create().also {
        it.title = title
        it.url = url
        it.thumbnail_url = thumbnail
    }

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(manifest().map { it.toSManga() }, hasNextPage = false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(
        manifest()
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { it.toSManga() },
        hasNextPage = false,
    )

    // Manifest URLs are absolute, so the inherited baseUrl + url concatenation would be wrong.
    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // The library keeps the URL a title was added with, so a host swap in the manifest
        // would never reach it. Re-resolve by title; if GitHub is down, the saved URL still works.
        // ponytail: one manifest fetch per title per update, cache it if libraries get large.
        val entry = runCatching { manifest().firstOrNull { it.title == manga.title } }.getOrNull()
        val site = (entry?.url ?: manga.url).toHttpUrl()

        return SMangaUpdate(
            manga = if (fetchDetails) details(manga, site, entry?.thumbnail) else manga,
            chapters = if (fetchChapters) chapterList(site) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = chapter.url.toHttpUrl()

        // REST first: one small request that comes back empty on a Madara host, which is the
        // signal to parse the chapter page instead. Sniffing the 120 KB chapter page up front
        // would make every WordPress read pay for the Madara one.
        val images = restImages(url).ifEmpty { madaraImages(url) }

        return images
            .ifEmpty { throw Exception("No images found for ${chapter.url}") }
            .mapIndexed { index, image -> Page(index, imageUrl = image) }
    }

    private suspend fun details(manga: SManga, site: HttpUrl, pinned: String?): SManga {
        val document = client.get(site, headers).asJsoup()

        return SManga.create().also {
            it.title = manga.title
            it.url = manga.url
            // The saved cover can point at a host the manifest has since swapped away from, so
            // a manifest-pinned cover wins, then the current site's og:image. The first <img>
            // is a guess (often a logo), so it only fills in when nothing better exists.
            it.thumbnail_url = pinned
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: manga.thumbnail_url
                ?: document.selectFirst("img[src]")?.absUrl("src")
            it.description = document.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    private suspend fun chapterList(site: HttpUrl): List<SChapter> {
        // A Madara entry has to name one series, because the host carries thousands of them; a
        // REST entry names the site root, because the whole feed is the one series. That
        // difference is load-bearing, so it doubles as the dispatch -- and it beats probing,
        // because an aggregator runs an SEO blog full of posts titled "... Chapter 71 English
        // Sub", which passes any title-shaped test on wp/v2/posts.
        if (site.pathSegments.any(String::isNotEmpty)) {
            madaraChapters(site).takeIf { it.isNotEmpty() }?.let { return it }
        }

        return restChapters(site)
    }

    private suspend fun madaraChapters(site: HttpUrl): List<SChapter> {
        val response = client.post(
            site.toString().trimEnd('/') + AJAX_CHAPTERS,
            headers,
            EMPTY_BODY,
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        return response.asJsoup().select("li.wp-manga-chapter").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val chapterName = link.text()

            SChapter.create().also {
                it.name = chapterName
                it.url = link.absUrl("href")
                it.chapter_number = chapterNumber(chapterName)
                it.date_upload = MADARA_DATE.tryParseDate(
                    element.selectFirst("span.chapter-release-date")?.text(),
                    ZoneOffset.UTC,
                )
            }
        }.sortedByDescending { it.chapter_number }
    }

    private suspend fun restChapters(site: HttpUrl): List<SChapter> {
        val type = postType(site)

        val posts = buildList {
            var page = 1
            while (page <= MAX_PAGES) {
                val response = client.get(
                    restUrl(site, type, "per_page=$PER_PAGE&page=$page&_fields=slug,title,date_gmt"),
                    headers,
                    ensureSuccess = false,
                )
                // WordPress answers 400 once `page` runs past the last one.
                if (!response.isSuccessful) {
                    response.close()
                    break
                }

                val batch = response.parseAs<List<PostDto>>()
                addAll(batch)
                if (batch.size < PER_PAGE) break
                page++
            }
        }

        return posts.map { post ->
            val chapterName = Parser.unescapeEntities(post.title.rendered, false)

            SChapter.create().also {
                it.name = chapterName
                it.url = "${site.scheme}://${site.host}/manga/${post.slug}/"
                // Publish date is not chapter order on these sites: side stories get
                // back-filled, so ch. 36.5 can be posted after ch. 111. The number has to
                // come from the title.
                it.chapter_number = chapterNumber(chapterName)
                it.date_upload = WP_DATE.tryParseDateTime(post.dateGmt, ZoneOffset.UTC)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private suspend fun madaraImages(chapter: HttpUrl): List<String> {
        // Any query string bypasses the page cache. The cached copy of a chapter ships with an
        // empty reader, which is indistinguishable from a broken parser.
        val response = client.get(
            chapter.newBuilder().setQueryParameter("cache", "0").build(),
            headers,
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val document = response.asJsoup()

        // Madara numbers the real pages; the unnumbered images in the reader are site banners.
        return document.select("div.page-break img[id^=image-]")
            .ifEmpty { document.select("div.page-break img") }
            .mapNotNull { it.imageUrl() }
    }

    private suspend fun restImages(chapter: HttpUrl): List<String> {
        val slug = chapter.pathSegments.last(String::isNotEmpty)
        val response = client.get(
            restUrl(chapter, postType(chapter), "slug=$slug&_fields=content"),
            headers,
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val content = response.parseAs<List<PostDto>>().firstOrNull()?.content?.rendered
            ?: return emptyList()

        return Jsoup.parseBodyFragment(content, "${chapter.scheme}://${chapter.host}/")
            .select("img")
            .mapNotNull { it.imageUrl() }
    }

    // Sites differ on src vs data-src; absUrl resolves relative paths and returns "" for the
    // base64 placeholders a lazy-loading theme ships in place of the real image.
    private fun Element.imageUrl(): String? = sequenceOf("data-src", "src")
        .map { absUrl(it) }
        .firstOrNull(String::isNotBlank)
        ?.let(::fullSizeBlogger)

    // Blogger-hosted pages embed a sized thumbnail (/s320/); /s0/ is the original upload.
    private fun fullSizeBlogger(url: String): String = if (url.contains(BLOGGER_HOST)) url.replace(BLOGGER_SIZE, "/s0/") else url

    private fun chapterNumber(name: String): Float = CHAPTER_NUMBER.find(name)
        ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

    private val postTypes = ConcurrentHashMap<String, String>()

    // Chapters are a custom post type -- "comic" on some sites, "manga" on others -- or
    // plain posts. Probing once per host keeps per-site configuration out of the manifest.
    private suspend fun postType(site: HttpUrl): String {
        postTypes[site.host]?.let { return it }

        val type = CUSTOM_TYPES.firstOrNull { type ->
            val response = client.get(restUrl(site, type, "per_page=1"), headers, ensureSuccess = false)
            response.use { it.isSuccessful && it.body.string().trim() != "[]" }
        } ?: POST_TYPE

        return type.also { postTypes[site.host] = it }
    }

    private fun restUrl(site: HttpUrl, type: String, query: String) = "${site.scheme}://${site.host}/wp-json/wp/v2/$type?$query"

    companion object {
        private const val PER_PAGE = 100
        private const val MAX_PAGES = 50
        private const val COMIC_TYPE = "comic"
        private const val MANGA_TYPE = "manga"
        private const val POST_TYPE = "posts"
        private val CUSTOM_TYPES = listOf(COMIC_TYPE, MANGA_TYPE)
        private const val AJAX_CHAPTERS = "/ajax/chapters/"
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
        private val CHAPTER_NUMBER = Regex("""chapter\s*([\d.]+)""", RegexOption.IGNORE_CASE)
        private const val BLOGGER_HOST = "blogger.googleusercontent.com"
        private val BLOGGER_SIZE = Regex("""/s\d+/""")
        private val WP_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private val MADARA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)
    }
}

@Serializable
class Entry(
    val title: String,
    val url: String,
    val thumbnail: String? = null,
)

@Serializable
class PostDto(
    val slug: String = "",
    val title: RenderedDto = RenderedDto(),
    @SerialName("date_gmt") val dateGmt: String? = null,
    val content: RenderedDto? = null,
)

@Serializable
class RenderedDto(
    val rendered: String = "",
)
