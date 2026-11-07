package com.ghosttap.core.vnic

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Live internet research subsystem — the piece the Python VNIC did NOT have
 * (`process()` returned "research_required" with no adapter).
 *
 * Real HTTP only: real searches, real page fetches, real extracted text.
 * No mock responses, no static lookup as a substitute. Every finding keeps
 * its source URL and a confidence that is honest about where it came from:
 *
 * - 0.60 — a search-result hit (title + snippet only, page not fetched)
 * - 0.75 — the page was fetched and text extracted from it
 * - 0.90 — the same fact was corroborated by 2+ independent domains
 *
 * `verified` is ALWAYS false at the research level. A web finding is a lead
 * until on-device evidence corroborates it — only the execution bridge can
 * mark something verified, and only from a real device response.
 *
 * Providers are tried free-first. Keyed providers exist as explicit adapters
 * ([BraveSearchProvider], [SerperProvider]) and are used ONLY when their
 * config key is set; the exact missing config is named in
 * [VnicResearchConfig]. No key is ever stored in code.
 *
 * Limits (anti-abuse, same spirit as the existing WebResearcher): at most
 * [MAX_FETCHES_PER_QUERY] page fetches per query, connect timeout 8s, read
 * timeout 12s, User-Agent identifies the app.
 */
data class VnicFinding(
    val query: String,
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
    val confidence: Double,
    val evidence: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
    val verified: Boolean = false
)

data class VnicFetchedPage(
    val url: String,
    val title: String,
    val text: String,
    val links: List<String>
)

data class VnicResearchReport(
    val query: String,
    val findings: List<VnicFinding>,
    val providersUsed: List<String>,
    val fetchedPages: Int,
    val durationMs: Long
)

data class VnicResearchConfig(
    /** Brave Search API key. Unset by default — set VNIC_BRAVE_API_KEY to enable. */
    val braveApiKey: String? = null,
    /** Serper (Google Search API) key. Unset by default — set VNIC_SERPER_API_KEY to enable. */
    val serperApiKey: String? = null,
    val maxFetchesPerQuery: Int = 3,
    val connectTimeoutMs: Int = 8000,
    val readTimeoutMs: Int = 12000
)

interface VnicResearchProvider {
    val name: String
    suspend fun search(query: String, config: VnicResearchConfig): List<VnicFinding>
}

object VnicHttp {
    const val USER_AGENT = "GhostTap-VNIC/1.0 (Android; research)"

    fun get(url: String, headers: Map<String, String> = emptyMap(), connectTimeoutMs: Int = 8000, readTimeoutMs: Int = 12000): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/json,*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = if (stream != null) BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() } else ""
            code to body
        } finally {
            conn.disconnect()
        }
    }
}

/** Minimal JSON string-field extractor (no JSON dependency in the pure core). */
object VnicJson {
    /** Returns the string value of "key":"..." handling \" \\ \/ \n \t \uXXXX escapes, or null. */
    fun stringField(json: String, key: String, fromIndex: Int = 0): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle, fromIndex)
        while (i >= 0) {
            var j = i + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                j++
                while (j < json.length && json[j].isWhitespace()) j++
                if (j < json.length && json[j] == '"') {
                    val sb = StringBuilder()
                    j++
                    while (j < json.length) {
                        val c = json[j]
                        when {
                            c == '\\' && j + 1 < json.length -> {
                                when (val e = json[j + 1]) {
                                    '"' -> sb.append('"')
                                    '\\' -> sb.append('\\')
                                    '/' -> sb.append('/')
                                    'n' -> sb.append('\n')
                                    't' -> sb.append('\t')
                                    'r' -> sb.append('\r')
                                    'u' -> {
                                        val hex = json.substring((j + 2).coerceAtMost(json.length), (j + 6).coerceAtMost(json.length))
                                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                        j += 4
                                    }
                                    else -> sb.append(e)
                                }
                                j += 2
                            }
                            c == '"' -> return sb.toString()
                            else -> { sb.append(c); j++ }
                        }
                    }
                    return sb.toString()
                }
                return null
            }
            i = json.indexOf(needle, i + 1)
        }
        return null
    }

    /** All occurrences of "key":"..." in document order. */
    fun allStringFields(json: String, key: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val v = stringField(json, key, from) ?: break
            out += v
            // Advance past this occurrence: find the closing quote of the value we just read.
            val idx = json.indexOf("\"$key\"", from)
            val colon = json.indexOf(':', idx + key.length + 2)
            val open = json.indexOf('"', colon)
            var close = open + 1
            while (close < json.length) {
                if (json[close] == '\\') close += 2 else if (json[close] == '"') break else close++
            }
            from = close + 1
        }
        return out
    }
}

/** HTML -> plain text: drop script/style, strip tags, decode common entities. */
object VnicHtml {
    fun toText(html: String): String {
        var t = html
        t = Regex("(?is)<script.*?</script>").replace(t, " ")
        t = Regex("(?is)<style.*?</style>").replace(t, " ")
        t = Regex("(?is)<title[^>]*>.*?</title>").replace(t, " ")
        t = Regex("(?is)<!--.*?-->").replace(t, " ")
        t = Regex("(?is)<[^>]+>").replace(t, " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ")
        t = Regex("&#(\\d+);").replace(t) { m -> (m.groupValues[1].toIntOrNull() ?: 63).toChar().toString() }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> (m.groupValues[1].toIntOrNull(16) ?: 63).toChar().toString() }
        return Regex("\\s+").replace(t, " ").trim()
    }

    fun titleOf(html: String): String {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return ""
        return toText(m.groupValues[1]).take(200)
    }

    fun linksOf(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("(?is)<a[^>]+href=[\"']([^\"'#]+)[\"']").findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.startsWith("http://") || href.startsWith("https://")) out += href
            else if (href.startsWith("/")) {
                try {
                    val u = URL(base)
                    out += "${u.protocol}://${u.host}$href"
                } catch (_: Exception) { /* skip malformed base */ }
            }
        }
        return out.take(20)
    }
}

/** DuckDuckGo HTML results — free, no key. Same endpoint family as the existing WebResearcher. */
class DuckDuckGoProvider : VnicResearchProvider {
    override val name = "duckduckgo"
    override suspend fun search(query: String, config: VnicResearchConfig): List<VnicFinding> {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val (code, body) = VnicHttp.get(url, connectTimeoutMs = config.connectTimeoutMs, readTimeoutMs = config.readTimeoutMs)
        if (code != 200) return emptyList()
        return parseResults(query, body)
    }

    /** Pure parse step — testable against a real captured response body without network. */
    fun parseResults(query: String, body: String): List<VnicFinding> {
        val out = mutableListOf<VnicFinding>()
        // Each hit lives in <div class="result results_links …">…</div>. Split
        // on the outer div only (with the trailing space) — inner divs are
        // result__extras etc. and must not fragment the block, or the title
        // and its snippet land in different pieces. Same approach as the
        // existing WebResearcher, with the outer-div fix.
        for (b in body.split("<div class=\"result ").drop(1).take(12)) {
            try {
                val aTag = Regex("<a[^>]*>").findAll(b)
                    .firstOrNull { it.value.contains("result__a") }?.value ?: continue
                val href = Regex("href=\"([^\"]+)\"").find(aTag)?.groupValues?.get(1) ?: continue
                val url = cleanDdgUrl(href) ?: continue
                val aStart = b.indexOf(aTag)
                val aEnd = b.indexOf("</a>", aStart)
                val title = (if (aEnd > aStart) VnicHtml.toText(b.substring(aStart + aTag.length, aEnd)).trim() else "")
                    .ifBlank { url }.take(200)
                val snippet = Regex(
                    "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(b)?.let { VnicHtml.toText(it.groupValues[1]).trim() } ?: ""
                out += VnicFinding(query, title, url, snippet.take(300), "duckduckgo", 0.60)
                if (out.size >= 5) break
            } catch (_: Exception) {
                // One bad block never breaks the parse.
            }
        }
        return out
    }

    /** DDG wraps outbound links: //duckduckgo.com/l/?uddg=<urlencoded>&rut=… — unwrap to the real URL. */
    fun cleanDdgUrl(href: String): String? {
        var h = href.trim()
        if (h.isBlank() || h.startsWith("#") || h.startsWith("javascript:", true)) return null
        val uddg = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)
        if (uddg != null) {
            return try {
                java.net.URLDecoder.decode(uddg, "UTF-8").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        if (h.startsWith("//")) h = "https:$h"
        if (!h.startsWith("http://") && !h.startsWith("https://")) return null
        return h
    }
}

/** Wikipedia search API — free, no key. */
class WikipediaProvider : VnicResearchProvider {
    override val name = "wikipedia"
    override suspend fun search(query: String, config: VnicResearchConfig): List<VnicFinding> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
            URLEncoder.encode(query, "UTF-8") + "&utf8=1&format=json&srlimit=5"
        val (code, body) = VnicHttp.get(url, connectTimeoutMs = config.connectTimeoutMs, readTimeoutMs = config.readTimeoutMs)
        if (code != 200) return emptyList()
        return parseResults(query, body)
    }

    /** Pure parse step — testable against a real captured response body without network. */
    fun parseResults(query: String, body: String): List<VnicFinding> {
        val titles = VnicJson.allStringFields(body, "title")
        val snippets = VnicJson.allStringFields(body, "snippet")
        return titles.take(5).mapIndexed { i, title ->
            val pageUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
            VnicFinding(query, title, pageUrl, VnicHtml.toText(snippets.getOrNull(i) ?: ""), "wikipedia", 0.60)
        }
    }
}

/** Brave Search API — keyed adapter. Only constructed/used when VNIC_BRAVE_API_KEY is set. */
class BraveSearchProvider(private val apiKey: String) : VnicResearchProvider {
    override val name = "brave"
    override suspend fun search(query: String, config: VnicResearchConfig): List<VnicFinding> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=" + URLEncoder.encode(query, "UTF-8") + "&count=5"
        val (code, body) = VnicHttp.get(
            url, mapOf("X-Subscription-Token" to apiKey),
            connectTimeoutMs = config.connectTimeoutMs, readTimeoutMs = config.readTimeoutMs
        )
        if (code != 200) return emptyList()
        val titles = VnicJson.allStringFields(body, "title")
        val urls = VnicJson.allStringFields(body, "url")
        val descs = VnicJson.allStringFields(body, "description")
        return titles.take(5).mapIndexed { i, title ->
            VnicFinding(query, title, urls.getOrNull(i) ?: "", descs.getOrNull(i) ?: "", "brave", 0.65)
        }.filter { it.url.isNotBlank() }
    }
}

/** Serper (Google Search API) — keyed adapter. Only constructed/used when VNIC_SERPER_API_KEY is set. */
class SerperProvider(private val apiKey: String) : VnicResearchProvider {
    override val name = "serper"
    override suspend fun search(query: String, config: VnicResearchConfig): List<VnicFinding> {
        val url = "https://google.serper.dev/search"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("X-API-KEY", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", VnicHttp.USER_AGENT)
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            doOutput = true
        }
        val payload = "{\"q\":${jsonEscape(query)},\"num\":5}"
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) return emptyList()
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val titles = VnicJson.allStringFields(body, "title")
            val links = VnicJson.allStringFields(body, "link")
            val snippets = VnicJson.allStringFields(body, "snippet")
            titles.take(5).mapIndexed { i, title ->
                VnicFinding(query, title, links.getOrNull(i) ?: "", snippets.getOrNull(i) ?: "", "serper", 0.65)
            }.filter { it.url.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonEscape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/** Direct page fetch + content extraction + link following (up to [VnicResearchConfig.maxFetchesPerQuery] pages). */
class DirectFetchProvider(
    /** Transport override (tests only). Null = the real [VnicHttp.get] transport. */
    private val getOverride: ((String, VnicResearchConfig) -> Pair<Int, String>)? = null
) {
    fun fetch(url: String, config: VnicResearchConfig): VnicFetchedPage? {
        return try {
            val (code, body) = getOverride?.invoke(url, config)
                ?: VnicHttp.get(url, connectTimeoutMs = config.connectTimeoutMs, readTimeoutMs = config.readTimeoutMs)
            if (code != 200 || body.isBlank()) return null
            VnicFetchedPage(url, VnicHtml.titleOf(body), VnicHtml.toText(body), VnicHtml.linksOf(body, url))
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The researcher. Runs free providers first (DuckDuckGo + Wikipedia), adds
 * keyed providers only when their keys are configured, fetches the top
 * result pages for real extracted evidence, and upgrades confidence when
 * two independent domains corroborate the same finding.
 */
/** Port the core depends on, so tests can substitute a stub researcher. */
interface VnicResearchPort {
    suspend fun research(query: String): VnicResearchReport
}

class VnicResearcher(
    private val config: VnicResearchConfig = VnicResearchConfig(),
    private val fetcher: DirectFetchProvider = DirectFetchProvider(),
    /** Injected provider list (tests only). Null = the real free-first provider set. */
    private val providersOverride: List<VnicResearchProvider>? = null
) : VnicResearchPort {
    fun providers(): List<VnicResearchProvider> {
        providersOverride?.let { return it }
        val list = mutableListOf<VnicResearchProvider>(DuckDuckGoProvider(), WikipediaProvider())
        config.braveApiKey?.takeIf { it.isNotBlank() }?.let { list += BraveSearchProvider(it) }
        config.serperApiKey?.takeIf { it.isNotBlank() }?.let { list += SerperProvider(it) }
        return list
    }

    override suspend fun research(query: String): VnicResearchReport {
        val started = System.currentTimeMillis()
        val used = mutableListOf<String>()
        val byUrl = LinkedHashMap<String, VnicFinding>()
        for (provider in providers()) {
            try {
                val hits = provider.search(query, config)
                if (hits.isNotEmpty()) used += provider.name
                for (h in hits) byUrl.putIfAbsent(h.url, h)
            } catch (_: Exception) {
                // A dead provider must not kill the research round.
            }
        }
        var fetched = 0
        val enriched = mutableListOf<VnicFinding>()
        for (finding in byUrl.values) {
            var f = finding
            if (fetched < config.maxFetchesPerQuery && finding.url.startsWith("http")) {
                val page = fetcher.fetch(finding.url, config)
                if (page != null) {
                    fetched++
                    val evidence = page.text.take(1500)
                    f = finding.copy(
                        confidence = 0.75,
                        evidence = evidence,
                        snippet = if (finding.snippet.isBlank()) evidence.take(300) else finding.snippet
                    )
                }
            }
            enriched += f
        }
        // Corroboration: the same normalised title words appearing from 2+ domains -> 0.90.
        val domainOf = { u: String -> try { URL(u).host.removePrefix("www.") } catch (_: Exception) { u } }
        val titleGroups = enriched.groupBy { it.title.lowercase().split(Regex("\\W+")).filter { w -> w.length > 3 }.toSet() }
        val corroborated = enriched.map { f ->
            val key = f.title.lowercase().split(Regex("\\W+")).filter { w -> w.length > 3 }.toSet()
            val domains = titleGroups[key]?.map { domainOf(it.url) }?.toSet().orEmpty()
            if (domains.size >= 2) f.copy(confidence = 0.90) else f
        }
        return VnicResearchReport(query, corroborated, used, fetched, System.currentTimeMillis() - started)
    }
}
