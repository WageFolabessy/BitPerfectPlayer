package com.cangkir.bitperfectplayer

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cangkir.bitperfectplayer.ui.BitPerfectAppRoot
import com.cangkir.bitperfectplayer.ui.MainViewModel
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val app = application as BitPerfectApplication
        val container = app.container
        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.factory(container),
            )
            val globalDynamicTokens by mainViewModel.globalDynamicThemeTokens.collectAsStateWithLifecycle()
            BitPerfectPlayerTheme(
                darkTheme = true,
                dynamicColor = false,
                dynamicTokens = globalDynamicTokens,
            ) {
                BitPerfectAppRoot(viewModel = mainViewModel)
            }
        }
        if (coldStartLogged.compareAndSet(false, true)) {
            window.decorView.post {
                val elapsedMs = (SystemClock.elapsedRealtime() - app.processCreateElapsedMs).coerceAtLeast(0L)
                container.diagnosticLogger.logPerf("COLD_START") {
                    mapOf("coldStartMs" to elapsedMs)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val container = (application as BitPerfectApplication).container
        container.playbackController.persistCurrentPositionNow()
        container.diagnosticLogger.requestFlush()
    }

    companion object {
        private val coldStartLogged = AtomicBoolean(false)
    }
}
