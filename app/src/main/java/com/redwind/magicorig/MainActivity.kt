package com.redwind.magicorig

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.redwind.magicorig.ui.MagicOriGAppCompose
import com.redwind.magicorig.ui.MagicOriGTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MagicOriGTheme {
                MagicOriGAppCompose()
            }
        }
    }
}

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        setContent {
            MagicOriGTheme {
                MagicOriGAppCompose()
            }
        }
    }
}
