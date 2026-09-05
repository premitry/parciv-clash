package com.github.kr328.clash

import android.content.ClipboardManager
import android.content.Context
import com.github.kr328.clash.common.model.ProxyNode
import com.github.kr328.clash.design.ConfigDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.ConfigDocument
import com.github.kr328.clash.util.ShareLink
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Layar Konfig: yang kelihatan cuma daftar node. "+" mengubah link di clipboard
 * jadi node, silang menghapus, dan setiap perubahan langsung ditulis jadi
 * config utuh oleh ConfigDocument lalu dimuat ke clash. Tidak ada tombol simpan
 * karena tidak ada yang perlu diketik.
 *
 * Profilnya sengaja bertipe File: profil Url akan mengunduh langganan lagi dan
 * membuang isi yang ditulis di sini.
 */
class ConfigActivity : BaseActivity<ConfigDesign>() {
    private var uuid: UUID? = null

    override suspend fun main() {
        val design = ConfigDesign(this)

        setContentDesign(design)

        val profile = resolveProfile()

        uuid = profile?.uuid

        design.setNodes(profile?.let { ConfigDocument.parse(readConfig(it)) } ?: emptyList())

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ServiceRecreated -> finish()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ConfigDesign.Request.ImportClipboard -> design.importClipboard()
                        is ConfigDesign.Request.Remove -> design.removeNode(it.index)
                    }
                }
            }
        }
    }

    /**
     * Profil aktif dipakai apa adanya kalau bertipe File, kalau tidak layar ini
     * pegang profil File miliknya sendiri. Tidak ada yang dibuat sampai node
     * pertama masuk.
     */
    private suspend fun resolveProfile(): Profile? {
        val active = withProfile { queryActive() }

        if (active != null && active.type == Profile.Type.File) return active

        val name = getString(R.string.parciv_profile_name)

        return withProfile { queryAll() }
            .firstOrNull { it.type == Profile.Type.File && it.name == name }
    }

    private suspend fun readConfig(profile: Profile): String = withContext(Dispatchers.IO) {
        val directory = if (profile.imported) importedDir else pendingDir
        val file = directory.resolve(profile.uuid.toString()).resolve("config.yaml")

        if (file.exists()) file.readText() else ""
    }

    private suspend fun ConfigDesign.importClipboard() {
        val clipboard = readClipboard()

        if (clipboard.isBlank()) {
            showToast(R.string.clipboard_empty, ToastDuration.Long)

            return
        }

        val incoming = ShareLink.extract(clipboard).mapNotNull { ShareLink.parse(it) }

        if (incoming.isEmpty()) {
            showToast(R.string.no_valid_link, ToastDuration.Long)

            return
        }

        val current = nodes.toList()
        val merged = ConfigDocument.dedupe(current + incoming)

        setNodes(merged)

        if (apply(merged)) {
            showToast(
                getString(R.string.format_nodes_imported, incoming.size),
                ToastDuration.Short,
            )
        } else {
            setNodes(current)
        }
    }

    private suspend fun ConfigDesign.removeNode(index: Int) {
        val current = nodes.toList()

        if (index !in current.indices) return

        val removed = current[index]
        val next = current.toMutableList().apply { removeAt(index) }

        setNodes(next)

        if (apply(next)) {
            showToast(
                getString(R.string.format_node_removed, removed.name),
                ToastDuration.Short,
            )
        } else {
            setNodes(current)
        }
    }

    private suspend fun readClipboard(): String = withContext(Dispatchers.Main) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip ?: return@withContext ""

        (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(this@ConfigActivity)?.toString() }
            .joinToString("\n")
    }

    /**
     * Tulis ulang seluruh config dari [values] lalu muat ke clash. Commit yang
     * gagal melepas salinan pending, jadi pemanggil balikin daftar node ke
     * keadaan sebelumnya supaya tampilan dan isi file tidak beda.
     */
    private suspend fun ConfigDesign.apply(values: List<ProxyNode>): Boolean {
        val content = ConfigDocument.build(values)

        return try {
            withProcessing { updateStatus ->
                val existing = uuid?.let { withProfile { queryByUUID(it) } }
                val target = existing?.uuid
                    ?: withProfile {
                        create(Profile.Type.File, getString(R.string.parciv_profile_name))
                    }

                var completed = false

                try {
                    // patch mengembalikan salinan imported ke pending, jadi harus
                    // jalan sebelum isi baru ditulis
                    if (existing != null) {
                        withProfile {
                            patch(
                                target,
                                existing.name,
                                existing.source,
                                existing.interval,
                                existing.ageSecretKey,
                            )
                        }
                    }

                    withContext(Dispatchers.IO) {
                        pendingDir.resolve(target.toString())
                            .apply { mkdirs() }
                            .resolve("config.yaml")
                            .writeText(content)
                    }

                    coroutineScope {
                        withProfile {
                            commit(target) { launch { updateStatus(it) } }
                        }
                    }

                    completed = true
                } finally {
                    if (!completed) withProfile { release(target) }
                }

                val profile = withProfile { queryByUUID(target) }

                if (profile != null && !profile.active) withProfile { setActive(profile) }

                uuid = target
            }

            true
        } catch (e: Exception) {
            showExceptionToast(e)

            false
        }
    }
}
