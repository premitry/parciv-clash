package com.github.kr328.clash.util

import android.util.Base64
import com.github.kr328.clash.common.model.ProxyNode
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Turns vless / vmess / trojan share links into clash-meta proxy entries,
 * the way Kentang Clash does on its "+" button.
 */
object ShareLink {
    private val schemes = listOf("vless://", "vmess://", "trojan-go://", "trojan://")

    private val inlineRegex = Regex(
        "(?:vless|vmess|trojan-go|trojan)://[^\\s\"'<>()\\[\\]]+",
        RegexOption.IGNORE_CASE
    )

    /**
     * Pulls every share link out of arbitrary clipboard text. A line that
     * already starts with a known scheme is taken whole, so names with
     * unescaped spaces in the #fragment survive; anything else is scanned for
     * links embedded in prose.
     */
    fun extract(text: String): List<String> {
        val result = ArrayList<String>()

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim().trim { it.isWhitespace() || it.code == 0xFEFF || it.code == 0x200B }

            if (line.isEmpty()) continue

            if (schemes.any { line.startsWith(it, ignoreCase = true) }) {
                result += line
            } else {
                inlineRegex.findAll(line).forEach { result += it.value }
            }
        }

        return result
    }

    fun parse(raw: String): ProxyNode? {
        val link = raw.trim()

        return try {
            when {
                link.startsWith("vless://", true) -> parseVless(link)
                link.startsWith("vmess://", true) -> parseVmess(link)
                link.startsWith("trojan-go://", true) -> parseTrojan(link)
                link.startsWith("trojan://", true) -> parseTrojan(link)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scalars first, nested blocks last: a nested block ends at the first line
     * with less indentation, so keeping the flat keys on top means callers can
     * emit them in any order without breaking the document.
     */
    private class Entry {
        private val scalars = ArrayList<String>()
        private val blocks = ArrayList<String>()

        fun scalar(key: String, value: String) {
            scalars += "$key: $value"
        }

        fun block(vararg lines: String) {
            blocks.addAll(lines.asList())
        }

        fun lines(): List<String> = scalars + blocks
    }

    private fun parseVless(raw: String): ProxyNode? {
        val parsed = split(raw) ?: return null
        val uuid = percentDecode(parsed.userInfo).takeIf { it.isNotBlank() } ?: return null
        val query = parsed.query
        val security = (query["security"] ?: "none").lowercase()
        val tls = security == "tls" || security == "reality" || security == "xtls"

        val entry = Entry()
        entry.scalar("type", "vless")
        entry.scalar("server", yaml(parsed.host))
        entry.scalar("port", parsed.port.toString())
        entry.scalar("uuid", yaml(uuid))
        entry.scalar("udp", "true")

        query["flow"]?.takeIf { it.isNotBlank() }?.let { entry.scalar("flow", yaml(it)) }

        entry.appendTls(query, tls, "servername", security == "reality")
        entry.appendNetwork(query, networkOf(query))

        return ProxyNode(parsed.fragment.ifBlank { "vless-" + parsed.host }, entry.lines())
    }

    private fun parseTrojan(raw: String): ProxyNode? {
        val parsed = split(raw) ?: return null
        val password = percentDecode(parsed.userInfo).takeIf { it.isNotBlank() } ?: return null
        val query = parsed.query

        val entry = Entry()
        entry.scalar("type", "trojan")
        entry.scalar("server", yaml(parsed.host))
        entry.scalar("port", parsed.port.toString())
        entry.scalar("password", yaml(password))
        entry.scalar("udp", "true")

        // trojan always runs over TLS
        entry.appendTls(query, true, "sni", false)

        // trojan-go links often omit the type and just carry a websocket path
        val network = query["type"]
            ?: query["net"]
            ?: if (!query["path"].isNullOrBlank()) "ws" else "tcp"

        entry.appendNetwork(query, normalizeNetwork(network))

        return ProxyNode(parsed.fragment.ifBlank { "trojan-" + parsed.host }, entry.lines())
    }

    private fun parseVmess(raw: String): ProxyNode? {
        val body = raw.substring(raw.indexOf("://") + 3)
        val head = body.substringBefore('#')
        val fragment = percentDecode(body.substringAfter('#', "")).trim()
        val decoded = decodeBase64(head)?.toString(Charsets.UTF_8)?.trim()

        // the common flavour is base64 of a json blob, the newer one looks like
        // a normal url
        if (decoded != null && decoded.startsWith("{")) {
            return parseVmessJson(decoded, fragment)
        }

        return parseVmessUrl(raw)
    }

    private fun parseVmessJson(text: String, fragment: String): ProxyNode? {
        val json = JSONObject(text)

        val server = json.opt("add")?.toString()?.trim().orEmpty()
        val uuid = json.opt("id")?.toString()?.trim().orEmpty()
        val port = json.opt("port")?.toString()?.trim()?.toIntOrNull() ?: return null

        if (server.isEmpty() || uuid.isEmpty() || port !in 1..65535) return null

        val alterId = (json.opt("aid") ?: json.opt("alterId"))
            ?.toString()?.trim()?.toIntOrNull() ?: 0
        val tlsRaw = json.opt("tls")?.toString()?.trim()?.lowercase().orEmpty()
        val tls = tlsRaw == "tls" || tlsRaw == "reality" || tlsRaw == "xtls"
        val cipher = json.opt("scy")?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"

        // reuse the url emitters by faking the query string they expect
        val query = LinkedHashMap<String, String>()
        json.opt("net")?.toString()?.takeIf { it.isNotBlank() }?.let { query["type"] = it }
        json.opt("host")?.toString()?.takeIf { it.isNotBlank() }?.let { query["host"] = it }
        json.opt("path")?.toString()?.takeIf { it.isNotBlank() }?.let { query["path"] = it }
        json.opt("sni")?.toString()?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        json.opt("alpn")?.toString()?.takeIf { it.isNotBlank() }?.let { query["alpn"] = it }
        json.opt("fp")?.toString()?.takeIf { it.isNotBlank() }?.let { query["fp"] = it }
        json.opt("type")?.toString()?.takeIf { it.isNotBlank() }?.let { query["headertype"] = it }

        val entry = Entry()
        entry.scalar("type", "vmess")
        entry.scalar("server", yaml(server))
        entry.scalar("port", port.toString())
        entry.scalar("uuid", yaml(uuid))
        entry.scalar("alterId", alterId.toString())
        entry.scalar("cipher", yaml(cipher))
        entry.scalar("udp", "true")

        entry.appendTls(query, tls, "servername", tlsRaw == "reality")
        entry.appendNetwork(query, networkOf(query))

        val name = fragment.ifBlank { json.opt("ps")?.toString()?.trim().orEmpty() }

        return ProxyNode(name.ifBlank { "vmess-" + server }, entry.lines())
    }

    private fun parseVmessUrl(raw: String): ProxyNode? {
        val parsed = split(raw) ?: return null
        val query = parsed.query
        val security = (query["security"] ?: query["tls"] ?: "none").lowercase()
        val tls = security == "tls" || security == "reality" || security == "xtls"

        // userinfo is either a bare uuid or base64 of "cipher:uuid"
        var cipher = query["encryption"]?.takeIf { it.isNotBlank() } ?: "auto"
        var uuid = percentDecode(parsed.userInfo)

        if (!uuid.contains('-')) {
            val decoded = decodeBase64(uuid)?.toString(Charsets.UTF_8)?.trim()

            if (decoded != null && decoded.contains(':')) {
                cipher = decoded.substringBefore(':').ifBlank { cipher }
                uuid = decoded.substringAfter(':')
            }
        }

        if (uuid.isBlank()) return null

        val entry = Entry()
        entry.scalar("type", "vmess")
        entry.scalar("server", yaml(parsed.host))
        entry.scalar("port", parsed.port.toString())
        entry.scalar("uuid", yaml(uuid))
        entry.scalar("alterId", (query["alterid"] ?: query["aid"])?.toIntOrNull()?.toString() ?: "0")
        entry.scalar("cipher", yaml(cipher))
        entry.scalar("udp", "true")

        entry.appendTls(query, tls, "servername", security == "reality")
        entry.appendNetwork(query, networkOf(query))

        return ProxyNode(parsed.fragment.ifBlank { "vmess-" + parsed.host }, entry.lines())
    }

    private fun Entry.appendTls(
        query: Map<String, String>,
        tls: Boolean,
        sniKey: String,
        reality: Boolean,
    ) {
        if (!tls) return

        scalar("tls", "true")

        val sni = (query["sni"] ?: query["peer"] ?: query["host"])?.takeIf { it.isNotBlank() }
        if (sni != null) scalar(sniKey, yaml(sni))

        val fingerprint = query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome".takeIf { reality }
        if (fingerprint != null) scalar("client-fingerprint", yaml(fingerprint))

        // bug host / zero rated setups never match the certificate, and that is
        // the entire point of them, so verification stays off unless the link
        // explicitly asks for it
        scalar("skip-cert-verify", skipCertVerify(query).toString())

        val alpn = query["alpn"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (alpn.isNotEmpty()) {
            block("alpn:", *alpn.map { "  - " + yaml(it) }.toTypedArray())
        }

        val publicKey = query["pbk"]?.takeIf { it.isNotBlank() }

        if (reality && publicKey != null) {
            val lines = ArrayList<String>()

            lines += "reality-opts:"
            lines += "  public-key: " + yaml(publicKey)
            query["sid"]?.takeIf { it.isNotBlank() }?.let { lines += "  short-id: " + yaml(it) }

            block(*lines.toTypedArray())
        }
    }

    private fun Entry.appendNetwork(query: Map<String, String>, network: String) {
        val host = (query["host"] ?: query["sni"])?.takeIf { it.isNotBlank() }
        val path = query["path"]?.takeIf { it.isNotBlank() }

        when (network) {
            "ws" -> {
                scalar("network", "ws")

                val lines = ArrayList<String>()

                lines += "ws-opts:"
                lines += "  path: " + yaml(path ?: "/")

                if (host != null) {
                    lines += "  headers:"
                    lines += "    Host: " + yaml(host)
                }

                block(*lines.toTypedArray())
            }
            "grpc" -> {
                scalar("network", "grpc")

                val serviceName = (query["servicename"] ?: path).orEmpty().trimStart('/')

                block("grpc-opts:", "  grpc-service-name: " + yaml(serviceName))
            }
            "h2" -> {
                scalar("network", "h2")

                val lines = ArrayList<String>()

                lines += "h2-opts:"

                if (host != null) {
                    lines += "  host:"
                    lines += "    - " + yaml(host)
                }

                lines += "  path: " + yaml(path ?: "/")

                block(*lines.toTypedArray())
            }
            "http" -> {
                scalar("network", "http")

                block(*httpOpts(host, path).toTypedArray())
            }
            else -> {
                // plain tcp only matters when it carries http obfuscation
                if (query["headertype"].equals("http", true)) {
                    scalar("network", "http")

                    block(*httpOpts(host, path).toTypedArray())
                }
            }
        }
    }

    private fun httpOpts(host: String?, path: String?): List<String> {
        val lines = ArrayList<String>()

        lines += "http-opts:"
        lines += "  method: GET"
        lines += "  path:"
        lines += "    - " + yaml(path ?: "/")

        if (host != null) {
            lines += "  headers:"
            lines += "    Host:"
            lines += "      - " + yaml(host)
        }

        return lines
    }

    private fun networkOf(query: Map<String, String>): String =
        normalizeNetwork(query["type"] ?: query["net"])

    private fun normalizeNetwork(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "ws", "websocket" -> "ws"
        "grpc" -> "grpc"
        "h2", "http2" -> "h2"
        "http" -> "http"
        else -> "tcp"
    }

    private fun skipCertVerify(query: Map<String, String>): Boolean {
        val raw = query["allowinsecure"]
            ?: query["insecure"]
            ?: query["skip-cert-verify"]
            ?: return true

        return !(raw == "0" || raw.equals("false", true))
    }

    private class Parsed(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    )

    /**
     * Hand rolled instead of Uri.parse: share links routinely carry raw spaces
     * and other unescaped bytes in the fragment, which Uri.parse rejects.
     */
    private fun split(raw: String): Parsed? {
        val separator = raw.indexOf("://")
        if (separator < 0) return null

        var rest = raw.substring(separator + 3)

        val hash = rest.indexOf('#')
        val fragment = if (hash >= 0) percentDecode(rest.substring(hash + 1)).trim() else ""
        if (hash >= 0) rest = rest.substring(0, hash)

        val mark = rest.indexOf('?')
        val query = if (mark >= 0) parseQuery(rest.substring(mark + 1)) else emptyMap()
        if (mark >= 0) rest = rest.substring(0, mark)

        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) rest.substring(0, at) else ""
        val hostPort = (if (at >= 0) rest.substring(at + 1) else rest).trim('/')

        val host: String
        val port: Int

        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null

            host = hostPort.substring(1, close)
            port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
        } else {
            val colon = hostPort.lastIndexOf(':')

            if (colon >= 0) {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = hostPort
                port = 443
            }
        }

        if (host.isBlank() || port !in 1..65535) return null

        return Parsed(userInfo, host, port, query, fragment)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        for (part in raw.split('&')) {
            if (part.isEmpty()) continue

            val equal = part.indexOf('=')
            val key = if (equal >= 0) part.substring(0, equal) else part
            val value = if (equal >= 0) part.substring(equal + 1) else ""

            result[percentDecode(key).lowercase()] = percentDecode(value)
        }

        return result
    }

    /**
     * URLDecoder is not usable here: it turns a literal plus into a space,
     * which corrupts base64 payloads embedded in query values.
     */
    private fun percentDecode(raw: String): String {
        if (!raw.contains('%')) return raw

        val out = ByteArrayOutputStream(raw.length)
        var index = 0

        while (index < raw.length) {
            val char = raw[index]

            if (char == '%' && index + 2 < raw.length) {
                val byte = raw.substring(index + 1, index + 3).toIntOrNull(16)

                if (byte != null) {
                    out.write(byte)
                    index += 3
                    continue
                }
            }

            out.write(char.toString().toByteArray(Charsets.UTF_8))
            index++
        }

        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** java.util.Base64 needs api 26, this app still ships down to api 21. */
    private fun decodeBase64(raw: String): ByteArray? {
        val cleaned = raw.trim()
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it == '\n' || it == '\r' || it == '=' }

        if (cleaned.isEmpty()) return null

        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)

        return try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }
}

/** Renders a scalar so it survives a yaml round trip, quoting only if needed. */
internal fun yaml(value: String): String {
    if (value.isEmpty()) return "\"\""

    val risky = value.first().isWhitespace() ||
        value.last().isWhitespace() ||
        value.any { it in ":#{}[]&*!|>'\"%@`,\n\t" } ||
        value.startsWith("-") ||
        value.equals("true", true) ||
        value.equals("false", true) ||
        value.equals("null", true) ||
        value == "~" ||
        value.toDoubleOrNull() != null

    if (!risky) return value

    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
