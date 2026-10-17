package com.glowled.app.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.glowled.app.core.LedController

/** Quick Settings tile to toggle the LED on/off. */
class LedTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        LedController.init(this)
        update()
    }

    override fun onClick() {
        super.onClick()
        val p = LedController.prefs
        p.enabled = !p.enabled
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (LedController.prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
