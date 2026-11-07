package com.ghosttap.core

import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * One place the researcher is allowed to look. This is DATA, not code —
 * new sources get added by appending to [ResearchSources.all], never by
 * rewriting the researcher.
 */
data class ResearchSource(val id: String, val label: String)

/** The web sources GHOST may consult. Extensible by appending. */
object ResearchSources {
    val all: List<ResearchSource> = listOf(
        ResearchSource("ddg-html", "DuckDuckGo HTML")
    )
}

/** One search hit: title, direct URL, and the engine's snippet. */
data class WebResult(val title: String, val url: String, val snippet: String)

/**
 * Real web research for the investigation loop. No new dependencies —
 * plain HttpURLConnection, already available on the phone.
 *
 * HONESTY CONTRACT (enforced, not suggested):
 * - Every finding from here has verified=false. Web text is "externally
 *   reported, unverified" — a lead, never a fact. verified=true is NEVER
 *   set by this researcher, no matter how convincing a page looks.
 * - Each finding is written to the timeline as an ASSUMED research record,
 *   so its provenance (exact URL) is permanent and auditable.
 * - Any failure — no network, blocked, unparseable — returns empty, never
 *   fabricated results. A failed search is a missing lead, not a dead end.
 * - Rate discipline: at most 3 page fetches per research call, 15s timeouts.
 *
 * The DuckDuckGo HTML endpoint is parsed heuristically (result__a anchors).
 * If DDG changes its markup, webSearch degrades to empty — the loop keeps
 * working on its on-device sources.
 */
object WebResearcher {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 15000
    private const val MAX_FETCHES = 3

    /**
     * Web search via the configured sources. Returns title/url/snippet hits,
     * best first. Empty list on any failure.
     */
    suspend fun webSearch(query: String, maxResults: Int = 5): List<WebResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            try {
                val url = "https://html.duckduckgo.com/html/?q=" +
                    URLEncoder.encode(query, "UTF-8")
                val html = httpGet(url, maxBytes = 300_000) ?: return@withContext emptyList()
                parseDdg(html).take(maxResults.coerceIn(1, 10))
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Fetch a page and return its visible text, scripts/styles stripped.
     * Empty string on any failure.
     */
    suspend fun fetchPageText(url: String, maxChars: Int = 6000): String =
        withContext(Dispatchers.IO) {
            try {
                val html = httpGet(url, maxBytes = 400_000) ?: return@withContext ""
                htmlToText(html).take(maxChars.coerceIn(500, 20000))
            } catch (_: Exception) {
                ""
            }
        }

    /**
     * Full research pass for one query: search, fetch up to [MAX_FETCHES]
     * pages, emit unverified findings, and record each on the timeline as
     * ASSUMED so the URL provenance is permanent.
     */
    suspend fun research(
        query: String,
        db: IntelDatabase,
        investigationId: String
    ): List<Finding> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<Finding>()
        val results = try {
            webSearch(query, 5)
        } catch (_: Exception) {
            emptyList()
        }
        for (r in results.take(MAX_FETCHES)) {
            val pageText = try {
                fetchPageText(r.url)
            } catch (_: Exception) {
                ""
            }
            if (pageText.isBlank()) continue
            val text = (r.title + " — " + pageText.take(400)).take(600)
            out.add(
                Finding(
                    text = text,
                    source = "web: ${r.url}",
                    confidence = 0.4,
                    verified = false // externally reported — a lead, never a fact
                )
            )
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = investigationId,
                        ts = System.currentTimeMillis(),
                        kind = "research",
                        title = r.url.take(160),
                        detail = ("Web finding (unverified, externally reported): " + text).take(1200),
                        provenance = "ASSUMED",
                        confidence = 0.4
                    )
                )
            } catch (_: Exception) {
                // A failed write must never lose the finding itself.
            }
        }
        return out
    }

    // ---------- HTTP ----------

    private fun httpGet(url: String, maxBytes: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            if (conn.responseCode !in 200..299) return null
            // Manual bounded read (minSdk 26 — no readNBytes).
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            conn.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null // absurdly large — skip
                    out.write(buf, 0, n)
                }
            }
            out.toString("UTF-8")
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- DuckDuckGo HTML parsing (heuristic) ----------

    private fun parseDdg(html: String): List<WebResult> {
        val out = mutableListOf<WebResult>()
        // Each hit lives in <div class="result ...">…</div>. Split and scan.
        val blocks = html.split("<div class=\"result").drop(1)
        for (b in blocks.take(12)) {
            try {
                // First anchor carrying result__a is the title link.
                val aTag = Regex("<a[^>]*>").findAll(b)
                    .firstOrNull { it.value.contains("result__a") }
                    ?.value ?: continue
                val href = Regex("href=\"([^\"]+)\"").find(aTag)
                    ?.groupValues?.get(1) ?: continue
                val url = cleanDdgUrl(href) ?: continue
                val aStart = b.indexOf(aTag)
                val aEnd = b.indexOf("</a>", aStart)
                val title = if (aEnd > aStart) {
                    stripTags(b.substring(aStart + aTag.length, aEnd)).trim()
                } else {
                    ""
                }.ifBlank { url }
                val snippet = Regex(
                    "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(b)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
                out.add(WebResult(title.take(200), url, snippet.take(300)))
            } catch (_: Exception) {
                // One bad block never breaks the parse.
            }
        }
        return out
    }

    private fun cleanDdgUrl(href: String): String? {
        var h = href.trim()
        if (h.isBlank() || h.startsWith("#") || h.startsWith("javascript:", true)) return null
        // DDG wraps outbound links: //duckduckgo.com/l/?uddg=<urlencoded>&rut=…
        val uddg = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)
        if (uddg != null) {
            return try {
                URLDecoder.decode(uddg, "UTF-8").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        if (h.startsWith("//")) h = "https:$h"
        if (!h.startsWith("http://") && !h.startsWith("https://")) return null
        return h
    }

    // ---------- HTML → text ----------

    private fun stripTags(s: String): String = Regex("<[^>]+>").replace(s, " ")

    private fun htmlToText(html: String): String {
        var t = html
        val dotAll = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        t = Regex("<script[^>]*>.*?</script>", dotAll).replace(t, " ")
        t = Regex("<style[^>]*>.*?</style>", dotAll).replace(t, " ")
        t = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)).replace(t, " ")
        t = Regex("<[^>]+>").replace(t, " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        t = Regex("&#(\\d+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
