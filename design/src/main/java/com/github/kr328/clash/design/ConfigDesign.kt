package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.common.model.ProxyNode
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.adapter.ConfigNodeAdapter
import com.github.kr328.clash.design.databinding.DesignConfigBinding
import com.github.kr328.clash.design.dialog.ModelProgressBarConfigure
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Satu layar berisi daftar node, tidak ada yaml yang kelihatan: "+" mengubah
 * link di clipboard jadi node, tombol silang menghapusnya. Sisi config yang
 * lain (grup, dns, rules) digenerate ConfigDocument di belakang layar, jadi
 * tidak ada yang bisa dirusak dari sini.
 */
class ConfigDesign(context: Context) : Design<ConfigDesign.Request>(context) {
    sealed class Request {
        object ImportClipboard : Request()
        data class Remove(val index: Int) : Request()
    }

    private val binding = DesignConfigBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ConfigNodeAdapter(context, ArrayList(), this::requestRemove)

    override val root: View
        get() = binding.root

    val processing: Boolean
        get() = binding.processing

    val nodes: List<ProxyNode>
        get() = adapter.values

    suspend fun setNodes(values: List<ProxyNode>) {
        withContext(Dispatchers.Main) {
            adapter.values.apply {
                clear()
                addAll(values)
            }

            adapter.notifyDataSetChanged()

            binding.hasNodes = values.isNotEmpty()
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

    init {
        binding.self = this
        binding.hasNodes = false

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    fun requestImportClipboard() {
        requests.trySend(Request.ImportClipboard)
    }

    private fun requestRemove(index: Int) {
        requests.trySend(Request.Remove(index))
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
