package com.advr.luaeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import com.advr.luaeditor.data.EditorSettings
import com.advr.luaeditor.data.EditorViewModel
import com.advr.luaeditor.ui.AdvrLuaTheme
import com.advr.luaeditor.ui.EditorScreen

class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings = vm.settings
            val dark = when (settings.themeMode) {
                EditorSettings.THEME_DARK -> true
                EditorSettings.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            AdvrLuaTheme(dark = dark) {
                EditorScreen(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app should not lose work; SAF writes are cheap enough to do on every pause.
        vm.saveAll()
    }
}
