package com.cangkir.bitperfectplayer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cangkir.bitperfectplayer.ui.BitPerfectAppRoot
import com.cangkir.bitperfectplayer.ui.MainViewModel
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as BitPerfectApplication).container
        setContent {
            BitPerfectPlayerTheme(darkTheme = true, dynamicColor = false) {
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModel.factory(container),
                )
                BitPerfectAppRoot(viewModel = mainViewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as BitPerfectApplication).container.playbackController.persistCurrentPositionNow()
    }
}
