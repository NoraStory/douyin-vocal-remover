package com.nora.douyinremover

import android.content.Intent
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
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * 接收系统分享（ACTION_SEND text/plain，manifest 已声明 intent-filter）：
     * 从抖音/浏览器分享到本 App 时，自动把分享文本填入输入框。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return
        if (text.isBlank()) return
        viewModel.updateInput(text)
    }
}
