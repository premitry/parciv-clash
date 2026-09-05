package com.github.kr328.clash.util

/**
 * Append only editing of a clash config document.
 *
 * Whatever the user typed is never reformatted or round tripped through a yaml
 * parser: new nodes are spliced into the existing proxies block as text and
 * their names are registered in every proxy group that already lists proxies.
 * A document that is still empty gets the full working skeleton instead.
 */
object ConfigDocument {
    private const val GROUP_SELECT = "PROXY"
    private const val GROUP_AUTO = "AUTO"
    private const val HEALTH_CHECK_URL = "http://cp.cloudflare.com/generate_204"

    private val nameRegex = Regex("^\\s*(?:-\\s*)?name\\s*:\\s*(.+)")

    /**
     * [offset] is where the first freshly inserted line starts, so the editor
     * can put the cursor on it and scroll it into view.
     */
    data class Result(val text: String, val names: List<String>, val offset: Int)

    fun append(source: String, nodes: List<ProxyNode>): Result {
        if (nodes.isEmpty()) return Result(source, emptyList(), 0)

        val text = source.replace("\r\n", "\n").replace('\r', '\n')

        if (text.isBlank()) return skeleton(dedupe(nodes, HashSet()))

        val lines = ArrayList(text.split("\n"))
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)

        val resolved = dedupe(nodes, collectNames(lines, 0, lines.size).toHashSet())
        val names = resolved.map { it.name }

        val inserted = insertProxies(lines, resolved)

        // everything below only touches lines after the insertion, so the index
        // stays valid while the rest of the document grows
        registerNames(lines, names)

        if (indexOfTopLevel(lines, "proxy-groups") < 0) {
            val start = indexOfTopLevel(lines, "proxies")
            val known = collectNames(lines, start + 1, blockEndOf(lines, start))

            lines += generateGroups(known)
        }

        if (indexOfTopLevel(lines, "rules") < 0) {
            lines += ""
            lines += "rules:"
            lines += "  - MATCH," + (firstGroupName(lines) ?: GROUP_SELECT)
        }

        return Result(lines.joinToString("\n") + "\n", names, offsetOf(lines, inserted))
    }

    private fun skeleton(nodes: List<ProxyNode>): Result {
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
        lines += "proxies:"

        val inserted = lines.size

        nodes.forEach { lines += render(it, "  ") }

        lines += generateGroups(nodes.map { it.name })
        lines += ""
        lines += "rules:"
        lines += "  - MATCH," + GROUP_SELECT

        return Result(
            lines.joinToString("\n") + "\n",
            nodes.map { it.name },
            offsetOf(lines, inserted),
        )
    }

    private fun offsetOf(lines: List<String>, index: Int): Int {
        var offset = 0

        for (position in 0 until minOf(index, lines.size)) {
            offset += lines[position].length + 1
        }

        return offset
    }

    private fun generateGroups(names: List<String>): List<String> {
        val lines = ArrayList<String>()

        lines += ""
        lines += "proxy-groups:"
        lines += "  - name: " + GROUP_SELECT
        lines += "    type: select"
        lines += "    proxies:"
        lines += "      - " + GROUP_AUTO
        names.forEach { lines += "      - " + yaml(it) }
        lines += "      - DIRECT"
        lines += "  - name: " + GROUP_AUTO
        lines += "    type: fallback"
        lines += "    url: " + HEALTH_CHECK_URL
        lines += "    interval: 60"
        lines += "    tolerance: 50"
        lines += "    proxies:"
        names.forEach { lines += "      - " + yaml(it) }

        return lines
    }

    private fun insertProxies(lines: MutableList<String>, nodes: List<ProxyNode>): Int {
        var start = indexOfTopLevel(lines, "proxies")

        if (start < 0) {
            lines += ""
            lines += "proxies:"
            start = lines.size - 1
        }

        // "proxies: []" from a template has to become a block list before
        // anything can be appended under it
        val inline = lines[start].trimEnd().removePrefix("proxies:").trim()
        if (inline == "[]" || inline == "[ ]") lines[start] = "proxies:"

        val end = blockEndOf(lines, start)
        val indent = itemIndentOf(lines, start + 1, end)
        val rendered = ArrayList<String>()

        nodes.forEach { rendered += render(it, indent) }

        lines.addAll(end, rendered)

        return end
    }

    private fun render(node: ProxyNode, indent: String): List<String> {
        val keyIndent = indent + "  "
        val lines = ArrayList<String>()

        lines += indent + "- name: " + yaml(node.name)
        node.lines.forEach { lines += keyIndent + it }

        return lines
    }

    /**
     * Adds every new name to each group that already carries a proxies list, so
     * a freshly pasted node is immediately selectable and part of the failover
     * pool. Edits run bottom up to keep the indices below them valid.
     */
    private fun registerNames(lines: MutableList<String>, names: List<String>) {
        val start = indexOfTopLevel(lines, "proxy-groups")
        if (start < 0) return

        val end = blockEndOf(lines, start)
        val blockEdits = ArrayList<Pair<Int, String>>()
        val flowEdits = ArrayList<Int>()

        var index = start + 1

        while (index < end) {
            val line = lines[index]
            val trimmed = line.trim()

            if (!trimmed.startsWith("proxies:")) {
                index++
                continue
            }

            if (trimmed.removePrefix("proxies:").trim().startsWith("[")) {
                flowEdits += index
                index++
                continue
            }

            val keyIndent = line.takeWhile { it.isWhitespace() }
            var itemIndent = keyIndent + "  "
            var last = index
            var scan = index + 1

            while (scan < end) {
                val candidate = lines[scan]

                if (candidate.isBlank()) {
                    scan++
                    continue
                }

                val candidateIndent = candidate.takeWhile { it.isWhitespace() }
                if (candidateIndent.length <= keyIndent.length) break

                if (candidate.trim().startsWith("- ")) {
                    itemIndent = candidateIndent
                    last = scan
                }

                scan++
            }

            blockEdits += (last + 1) to itemIndent
            index = scan
        }

        val rendered = names.map { yaml(it) }

        for (position in flowEdits.sortedDescending()) {
            val line = lines[position]
            val close = line.lastIndexOf(']')
            if (close < 0) continue

            val head = line.substring(0, close).trimEnd()
            val separator = if (head.endsWith("[") || head.endsWith(",")) "" else ", "

            lines[position] = head + separator + rendered.joinToString(", ") + line.substring(close)
        }

        for ((position, indent) in blockEdits.sortedByDescending { it.first }) {
            lines.addAll(position, rendered.map { indent + "- " + it })
        }
    }

    private fun dedupe(nodes: List<ProxyNode>, taken: MutableSet<String>): List<ProxyNode> =
        nodes.map { node ->
            var name = node.name.ifBlank { "node" }

            if (taken.contains(name)) {
                var suffix = 2
                while (taken.contains(name + " " + suffix)) suffix++
                name = name + " " + suffix
            }

            taken += name

            if (name == node.name) node else node.copy(name = name)
        }

    private fun indexOfTopLevel(lines: List<String>, key: String): Int =
        lines.indexOfFirst {
            it.isNotEmpty() && !it[0].isWhitespace() && it.trimEnd().startsWith(key + ":")
        }

    /**
     * Index right after the last line that still belongs to the block opened at
     * [start], which is also where new entries go.
     */
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

    private fun itemIndentOf(lines: List<String>, from: Int, until: Int): String {
        for (index in from until minOf(until, lines.size)) {
            val line = lines[index]
            val trimmed = line.trimStart()

            if (trimmed == "-" || trimmed.startsWith("- ")) {
                return line.substring(0, line.length - trimmed.length)
            }
        }

        return "  "
    }

    private fun collectNames(lines: List<String>, from: Int, until: Int): List<String> {
        val result = ArrayList<String>()

        for (index in maxOf(from, 0) until minOf(until, lines.size)) {
            val match = nameRegex.find(lines[index]) ?: continue

            result += unquote(match.groupValues[1])
        }

        return result
    }

    private fun firstGroupName(lines: List<String>): String? {
        val start = indexOfTopLevel(lines, "proxy-groups")
        if (start < 0) return null

        return collectNames(lines, start + 1, blockEndOf(lines, start)).firstOrNull()
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
