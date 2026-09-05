package com.github.kr328.clash

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.kr328.clash.design.R

/**
 * Hanya buat nyala/mati/nyambung-ulang dari shortcut launcher dan tombol
 * notifikasi. Jalur `clash://install-config` dibuang: satu-satunya sumber
 * config di fork ini adalah editor Konfig, bukan langganan dari URL.
 */
class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        when(intent.action) {
            Intents.ACTION_TOGGLE_CLASH -> {
                if (isClashRunning()) {
                    stopClash()
                } else {
                    startClash()
                }
            }
            
            Intents.ACTION_START_CLASH -> {
                if (isClashRunning()) {
                    Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
                } else {
                    startClash()
                }
            }
            
            Intents.ACTION_STOP_CLASH -> {
                stopClash()
            }

            Intents.ACTION_RESTART_CLASH -> {
                restartClash()

                return
            }
        }
        return finish()
    }

    private fun isClashRunning(): Boolean {
        return StatusClient(this).currentProfile() != null
    }

    private fun startClash() {
//        if (currentProfile == null) {
//            Toast.makeText(this, R.string.no_profile_selected, Toast.LENGTH_LONG).show()
//            return
//        }
        val vpnRequest = startClashService()
        if (vpnRequest != null) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
    }

    /**
     * Reconnect from the notification action: the tunnel has to be fully down
     * before it can come back up, otherwise the new one races the teardown of
     * the old one and the vpn interface is left half configured.
     */
    private fun restartClash() {
        launch {
            if (withContext(Dispatchers.IO) { isClashRunning() }) {
                stopClashService()

                var waited = 0L

                while (waited < 5000L && withContext(Dispatchers.IO) { isClashRunning() }) {
                    delay(250)

                    waited += 250
                }

                delay(250)
            }

            val vpnRequest = startClashService()

            Toast.makeText(
                this@ExternalControlActivity,
                if (vpnRequest != null) R.string.unable_to_start_vpn else R.string.external_control_restarting,
                Toast.LENGTH_LONG,
            ).show()

            finish()
        }
    }

    private fun stopClash() {
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
