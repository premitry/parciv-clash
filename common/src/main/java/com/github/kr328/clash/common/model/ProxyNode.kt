package com.github.kr328.clash.common.model

/**
 * Satu entri di blok `proxies:` sebuah config clash. [lines] adalah isinya
 * tanpa penanda list dan tanpa indentasi luar; blok bersarang di dalam [lines]
 * bawa indentasi relatifnya sendiri, kedalaman luarnya diatur ConfigDocument.
 *
 * Tinggal di :common karena dipakai dua sisi: app yang mengubah link jadi node
 * dan menulis config, dan design yang menampilkan daftarnya di layar Konfig.
 */
data class ProxyNode(val name: String, val lines: List<String>) {
    val type: String
        get() = valueOf("type")

    val server: String
        get() = valueOf("server")

    val port: String
        get() = valueOf("port")

    /** Baris kedua di daftar node, misal "vless / 1.2.3.4:443". */
    val summary: String
        get() {
            val address = when {
                server.isEmpty() -> ""
                port.isEmpty() -> server
                else -> "$server:$port"
            }

            return listOf(type, address).filter { it.isNotEmpty() }.joinToString(" / ")
        }

    /**
     * Hanya kunci di kedalaman terluar yang dibaca, supaya `server` milik blok
     * bersarang seperti ws-opts atau reality-opts tidak ikut kebaca.
     */
    private fun valueOf(key: String): String {
        val prefix = "$key:"

        val raw = lines.firstOrNull {
            it.isNotEmpty() && !it[0].isWhitespace() && it.startsWith(prefix)
        } ?: return ""

        return raw.removePrefix(prefix).trim().trim('"', '\'')
    }
}
