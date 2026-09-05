package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.databinding.DesignConfigBinding
import com.github.kr328.clash.design.dialog.ModelProgressBarConfigure
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ConfigDesign(context: Context) : Design<ConfigDesign.Request>(context) {
    enum class Request {
        ImportClipboard,
        Save,
    }

    private val binding = DesignConfigBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    val processing: Boolean
        get() = binding.processing

    var text: String
        get() = binding.configText.text?.toString() ?: ""
        set(value) {
            binding.configText.setText(value)
        }

    suspend fun setTextAsync(value: String) {
        withContext(Dispatchers.Main) {
            binding.configText.setText(value)
        }
    }

    /**
     * Kentang style: after a conversion the caret lands on the first line that
     * was just added, so the new node is what the user is looking at.
     */
    suspend fun setTextAsync(value: String, selection: Int) {
        withContext(Dispatchers.Main) {
            binding.configText.setText(value)
            binding.configText.setSelection(selection.coerceIn(0, value.length))
            binding.configText.requestFocus()
        }
    }

    suspend fun withProcessing(executeTask: suspend (suspend (FetchStatus) -> Unit) -> Unit) {
        try {
            binding.processing = true

            context.withModelProgressBar {
                configure {
                    isIndeterminate = true
                    text = context.getString(R.string.config_reloading)
                }

                executeTask {
                    configure {
                        applyFrom(it)
                    }
                }
            }
        } finally {
            binding.processing = false
        }
    }

    suspend fun requestExitWithoutSaving(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.exit_without_save)
                    .setMessage(R.string.exit_without_save_config)
                    .setCancelable(true)
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
                    .show()

                ctx.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
    }

    fun requestImportClipboard() {
        requests.trySend(Request.ImportClipboard)
    }

    fun requestSave() {
        requests.trySend(Request.Save)
    }

    private fun ModelProgressBarConfigure.applyFrom(status: FetchStatus) {
        when (status.action) {
            FetchStatus.Action.FetchConfiguration -> {
                text = context.getString(R.string.format_fetching_configuration, status.args[0])
                isIndeterminate = true
            }
            FetchStatus.Action.FetchProviders -> {
                text = context.getString(R.string.format_fetching_provider, status.args[0])
                isIndeterminate = false
                max = status.max
                progress = status.progress
            }
            FetchStatus.Action.SubscriptionInfo -> Unit
            FetchStatus.Action.Verifying -> {
                text = context.getString(R.string.verifying)
                isIndeterminate = false
                max = status.max
                progress = status.progress
            }
        }
    }
}
