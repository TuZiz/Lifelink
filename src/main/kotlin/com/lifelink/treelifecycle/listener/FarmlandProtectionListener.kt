package com.lifelink.treelifecycle.listener

import com.lifelink.treelifecycle.config.ConfigService
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEvent

class FarmlandProtectionListener(
    private val configService: ConfigService
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return
        if (!shouldProtect(event.clickedBlock?.type)) return

        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityInteract(event: EntityInteractEvent) {
        if (!shouldProtect(event.block.type)) return

        event.isCancelled = true
    }

    private fun shouldProtect(type: Material?): Boolean =
        configService.current().protection.preventFarmlandTrample && type == Material.FARMLAND
}
