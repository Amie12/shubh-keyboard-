package com.example.floatkb

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class KeyboardTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val svc = KeyboardService.instance
        if (svc == null) {
            // Service not enabled yet -> send user to enable it
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(i)
        } else {
            svc.toggle()
            refresh()
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (KeyboardService.instance?.isShowing == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
