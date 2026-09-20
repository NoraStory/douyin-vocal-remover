package com.nora.douyinremover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.nora.douyinremover.ui.AppViewModel
import com.nora.douyinremover.ui.DouyinRemoverApp
import com.nora.douyinremover.ui.theme.DouyinRemoverTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DouyinRemoverTheme {
                DouyinRemoverApp(viewModel)
            }
        }
    }
}
