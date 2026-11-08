package com.jjmluz.quemdiria

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel: RoomViewModel by viewModels()
    private val usedKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { QuemDiriaApp(viewModel) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground(true)
    }

    override fun onStop() {
        viewModel.onForeground(false)
        super.onStop()
    }

    // Keys go straight to the view model: nothing on screen is focusable. A used key's release is
    // swallowed too, so Back that took us to the lobby doesn't also close the app.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && viewModel.onKeyDown(keyCode)) usedKeys += keyCode
                if (keyCode in usedKeys) return true
            }
            KeyEvent.ACTION_UP -> if (usedKeys.remove(keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
