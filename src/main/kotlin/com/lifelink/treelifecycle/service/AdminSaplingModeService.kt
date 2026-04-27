package com.lifelink.treelifecycle.service

import com.lifelink.treelifecycle.util.Permissions
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AdminSaplingModeService {
    private val enabledPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun setEnabled(player: Player, enabled: Boolean): Boolean {
        if (enabled) {
            enabledPlayers.add(player.uniqueId)
        } else {
            enabledPlayers.remove(player.uniqueId)
        }
        return enabled
    }

    fun toggle(player: Player): Boolean {
        if (enabledPlayers.remove(player.uniqueId)) return false
        enabledPlayers.add(player.uniqueId)
        return true
    }

    fun isEnabled(player: Player): Boolean =
        player.uniqueId in enabledPlayers

    fun shouldRecordAsManaged(player: Player): Boolean =
        isEnabled(player) &&
            (player.hasPermission(Permissions.ADMIN_SAPLING_MODE) || player.hasPermission(Permissions.ADMIN))
}
