package com.github.kr328.clash

import android.content.ClipboardManager
import android.content.Context
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
 * One screen for the whole config, the way Kentang Clash does it: the yaml of a
 * single file profile plus a "+" that turns whatever share links are on the
 * clipboard into proxies right where they belong.
 *
 * The profile is deliberately of type File - committing a Url profile would
 * re-download the subscription and throw away everything typed here.
 */
class ConfigActivity : BaseActivity<ConfigDesign>() {
    private var uuid: UUID? = null
    private var saved: String = ""

    override suspend fun main() {
        val design = ConfigDesign(this)

        setContentDesign(design)

        val profile = resolveProfile()

        uuid = profile?.uuid
        saved = profile?.let { readConfig(it) } ?: ""

        design.setTextAsync(saved)

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
                        ConfigDesign.Request.Save -> design.saveConfig()
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        design?.apply {
            launch {
                if (!processing) {
                    if (text == saved || requestExitWithoutSaving()) finish()
                }
            }
        } ?: return super.onBackPressed()
    }

    /**
     * The active profile is used as-is when it is a file, otherwise the screen
     * owns a file profile of its own. Nothing is created until the first save,
     * so just looking at the editor does not litter the profile list.
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

        val nodes = ShareLink.extract(clipboard).mapNotNull { ShareLink.parse(it) }

        if (nodes.isEmpty()) {
            showToast(R.string.no_valid_link, ToastDuration.Long)

            return
        }

        val result = ConfigDocument.append(text, nodes)

        setTextAsync(result.text, result.offset)

        showToast(
            getString(R.string.format_nodes_imported, result.names.size),
            ToastDuration.Short,
        )
    }

    private suspend fun readClipboard(): String = withContext(Dispatchers.Main) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip ?: return@withContext ""

        (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(this@ConfigActivity)?.toString() }
            .joinToString("\n")
    }

    private suspend fun ConfigDesign.saveConfig() {
        val content = text

        if (content.isBlank()) {
            showToast(R.string.config_editor_hint, ToastDuration.Long)

            return
        }

        try {
            withProcessing { updateStatus ->
                val existing = uuid?.let { withProfile { queryByUUID(it) } }
                val target = existing?.uuid
                    ?: withProfile {
                        create(Profile.Type.File, getString(R.string.parciv_profile_name))
                    }

                var completed = false

                try {
                    // patch clones the imported files back into pending, so it
                    // has to run before the new text is written
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
                    // a failed commit leaves the imported copy untouched, and a
                    // profile that was created for this save disappears again
                    if (!completed) withProfile { release(target) }
                }

                val profile = withProfile { queryByUUID(target) }

                if (profile != null && !profile.active) withProfile { setActive(profile) }

                uuid = target
                saved = content
            }

            showToast(R.string.config_saved, ToastDuration.Short)
        } catch (e: Exception) {
            showExceptionToast(e)
        }
    }
}
