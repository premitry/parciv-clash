package com.github.kr328.clash.util

import com.github.kr328.clash.common.model.ProxyNode

/**
 * Config clash dibangun ulang penuh dari daftar node.
 *
 * Layar Konfig cuma menampilkan node, jadi sisanya (dns, proxy-groups, rules)
 * tidak pernah disentuh siapa pun dan aman digenerate ulang setiap simpan.
 * Dua grup yang selalu ada: PROXY untuk memilih manual, dan AUTO bertipe
 * fallback yang pindah sendiri kalau node teratas mati. Keduanya selalu berisi
 * seluruh node yang ada, jadi node baru langsung ikut tanpa diatur lagi.
 */
object ConfigDocument {
    private const val GROUP_SELECT = "PROXY"
    private const val GROUP_AUTO = "AUTO"
    private const val HEALTH_CHECK_URL = "http://cp.cloudflare.com/generate_204"

    private val itemRegex = Regex("""^\s*-\s*name\s*:\s*(.+)""")

    /**
     * Dokumen utuh dari [nodes]. Nama kembar otomatis dikasih nomor supaya
     * clash tidak menolak config karena nama proxy dobel.
     */
    fun build(nodes: List<ProxyNode>): String {
        val resolved = dedupe(nodes)
        val lines = ArrayList<String>()

        lines += "mixed-port: 7890"
        lines += "allow-lan: false"
        lines += "mode: rule"
        lines += "log-level: info"
        lines += "ipv6: false"
        lines += ""
        lines += "dns:"
        lines += "  enable: true"
        lines += "  ipv6: false"
        lines += "  nameserver:"
        lines += "    - 1.1.1.1"
        lines += "    - 8.8.8.8"
        lines += ""

        if (resolved.isEmpty()) {
            lines += "proxies: []"
        } else {
            lines += "proxies:"
            resolved.forEach { lines += render(it, "  ") }
        }

        lines += ""
        lines += "proxy-groups:"
        lines += "  - name: $GROUP_SELECT"
        lines += "    type: select"
        lines += "    proxies:"
        if (resolved.isNotEmpty()) lines += "      - $GROUP_AUTO"
        resolved.forEach { lines += "      - " + yaml(it.name) }
        lines += "      - DIRECT"

        // grup fallback tanpa anggota ditolak clash, jadi hanya ditulis kalau
        // memang ada node
        if (resolved.isNotEmpty()) {
            lines += "  - name: $GROUP_AUTO"
            lines += "    type: fallback"
            lines += "    url: $HEALTH_CHECK_URL"
            lines += "    interval: 60"
            lines += "    tolerance: 50"
            lines += "    proxies:"
            resolved.forEach { lines += "      - " + yaml(it.name) }
        }

        lines += ""
        lines += "rules:"
        lines += "  - MATCH,$GROUP_SELECT"

        return lines.joinToString("\n") + "\n"
    }

    /**
     * Baca balik blok `proxies:` jadi daftar node. Yang dibaca hanya config
     * hasil [build], jadi gaya blok sudah pasti; entri gaya flow dilewati.
     */
    fun parse(source: String): List<ProxyNode> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val start = indexOfTopLevel(lines, "proxies")

        if (start < 0) return emptyList()

        val end = blockEndOf(lines, start)
        val result = ArrayList<ProxyNode>()
        var index = start + 1

        while (index < end) {
            val match = itemRegex.find(lines[index])

            if (match == null) {
                index++
                continue
            }

            val itemIndent = indentOf(lines[index])
            val keyIndent = itemIndent + 2
            val body = ArrayList<String>()
            var scan = index + 1

            while (scan < end) {
                val candidate = lines[scan]

                if (candidate.isBlank()) {
                    scan++
                    continue
                }

                val indent = indentOf(candidate)
                if (indent <= itemIndent) break

                body += candidate.substring(minOf(keyIndent, indent))
                scan++
            }

            result += ProxyNode(unquote(match.groupValues[1]), body)
            index = scan
        }

        return result
    }

    /** Nama kembar dapat akhiran nomor, urutan lain tetap. */
    fun dedupe(nodes: List<ProxyNode>): List<ProxyNode> {
        val taken = HashSet<String>()

        return nodes.map { node ->
            var name = node.name.ifBlank { "node" }

            if (taken.contains(name)) {
                var suffix = 2
                while (taken.contains("$name $suffix")) suffix++
                name = "$name $suffix"
            }

            taken += name

            if (name == node.name) node else node.copy(name = name)
        }
    }

    private fun render(node: ProxyNode, indent: String): List<String> {
        val keyIndent = "$indent  "
        val lines = ArrayList<String>()

        lines += indent + "- name: " + yaml(node.name)
        node.lines.forEach { lines += keyIndent + it }

        return lines
    }

    private fun indentOf(line: String): Int = line.takeWhile { it.isWhitespace() }.length

    private fun indexOfTopLevel(lines: List<String>, key: String): Int =
        lines.indexOfFirst {
            it.isNotEmpty() && !it[0].isWhitespace() && it.trimEnd().startsWith("$key:")
        }

    /** Indeks tepat setelah baris terakhir yang masih milik blok di [start]. */
    private fun blockEndOf(lines: List<String>, start: Int): Int {
        var index = start + 1
        var end = start + 1

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++
                continue
            }

            val nested = line[0].isWhitespace() || trimmed == "-" || trimmed.startsWith("- ")
            if (!nested) break

            index++
            end = index
        }

        return end
    }

    private fun unquote(raw: String): String {
        val value = raw.trim()

        return when {
            value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") ->
                value.substring(1, value.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            value.length >= 2 && value.startsWith("'") && value.endsWith("'") ->
                value.substring(1, value.length - 1).replace("''", "'")
            else -> value.substringBefore(" #").trim()
        }
    }
}
