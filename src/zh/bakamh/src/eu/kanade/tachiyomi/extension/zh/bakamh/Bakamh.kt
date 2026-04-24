package eu.kanade.tachiyomi.extension.zh.bakamh

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.zh.bakamh.BakamhPreferences.baseUrl
import eu.kanade.tachiyomi.extension.zh.bakamh.BakamhPreferences.preferenceMigration
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.UserAgentType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferences
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Bakamh :
    Madara(
        "巴卡漫画",
        BakamhPreferences.DEFAULT_DOMAIN,
        "zh",
        SimpleDateFormat("yyyy 年 M 月 d 日", Locale.CHINESE),
    ),
    ConfigurableSource {
    private val preferences = getPreferences { preferenceMigration() }

    override val baseUrl by lazy { preferences.baseUrl() }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(UserAgentClientHintsInterceptor())
        .rateLimit(permits = 2, period = 1, unit = TimeUnit.SECONDS) // Rate limit added to prevent 429 errors during library updates
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")
        .setRandomUserAgent(UserAgentType.MOBILE)

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        BakamhPreferences.buildPreferences(screen.context)
            .forEach(screen::addPreference)
    }

    override val mangaDetailsSelectorStatus = ".post-content_item:contains(状态) .summary-content"
    override fun chapterListSelector() = ".chapter-loveYou a, li:not(.menu-item) a[onclick], li:not(.menu-item) a"

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url.toString().lowercase()
        return response.asJsoup()
            .select(chapterListSelector())
            .mapNotNull { parseChapter(it, mangaUrl) }
    }

    private fun parseChapter(element: Element, mangaUrl: String): SChapter? {
        // Current URL attribute
        if (element.hasAttr("storage-chapter-url")) {
            return SChapter.create().apply {
                url = element.absUrl("storage-chapter-url")
                name = element.text()
                chapter_number = 0F
            }
        }

        // Compatibility operation for modified versions
        return element.attributes()
            .firstOrNull { attr ->
                val value = attr.value.lowercase()
                value.startsWith(mangaUrl) &&
                    value != mangaUrl && // Not current URL
                    !value.startsWith("$mangaUrl#comment") // Not comment
            }
            ?.let { attr ->
                SChapter.create().apply {
                    url = element.absUrl(attr.key)
                    name = element.text()
                    chapter_number = 0F
                }
            }
    }

    // 覆盖图片列表解析逻辑
    override fun pageListParse(document: Document): List<Page> {
        // 针对该站点的 img.wp-manga-chapter-img 标签进行解析
        return document.select("img.wp-manga-chapter-img").mapIndexed { i, element ->
            // 优先获取 data-manga-src，如果没有则获取 src
            // 关键点：必须调用 .trim() 去掉开头和结尾的空格
            val imageUrl = element.attr("data-manga-src")
                .ifEmpty { element.attr("src") }
                .trim()

            Page(i, document.location(), imageUrl)
        }
    }

    // 禁用基类的图片从元素解析逻辑，防止冲突
    override fun imageFromElement(element: Element): String {
        // 尝试获取 data-manga-src，如果为空则获取 src，并去掉首尾空格
        val imageUrl = element.attr("data-manga-src")
            .ifEmpty { element.attr("src") }
            .trim()

        return imageUrl
    }

}
