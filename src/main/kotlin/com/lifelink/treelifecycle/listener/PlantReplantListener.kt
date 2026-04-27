package com.lifelink.treelifecycle.listener

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.i18n.MessageService
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

class PlantReplantListener(
    private val configService: ConfigService,
    private val messageService: MessageService
) : Listener {
    private val playerSugarCaneBases = ConcurrentHashMap.newKeySet<BlockLocation>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type == Material.SUGAR_CANE) handleSugarCane(event)
    }

    private fun handleSugarCane(event: BlockBreakEvent) {
        val config = configService.current()
        if (!config.plants.sugarCane.leaveBase) return
        if (config.plants.sugarCane.respectSneakBypass && config.harvest.sneakBypassReplant && event.player.isSneaking) return

        val base = event.block
        if (base.getRelative(0, -1, 0).type == Material.SUGAR_CANE) return
        if (playerSugarCaneBases.remove(BlockLocation.from(base))) return

        event.isCancelled = true
        messageService.send(event.player, "protected-sapling")
        var removed = 0
        var current = base.getRelative(0, 1, 0)
        while (current.type == Material.SUGAR_CANE) {
            current.type = Material.AIR
            removed++
            current = current.getRelative(0, 1, 0)
        }

        if (removed > 0 && event.player.gameMode != GameMode.CREATIVE) {
            base.world.dropItemNaturally(
                base.location.add(0.5, 0.5, 0.5),
                ItemStack(Material.SUGAR_CANE, removed)
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.blockPlaced.type == Material.SUGAR_CANE) {
            playerSugarCaneBases.add(BlockLocation.from(findSugarCaneBase(event.blockPlaced)))
        }
        // 蘑菇补种功能已移除：大型蘑菇与蘑菇柄完全走原版放置/破坏逻辑。
    }

    private fun findSugarCaneBase(block: Block): Block {
        var current = block
        while (current.getRelative(0, -1, 0).type == Material.SUGAR_CANE) {
            current = current.getRelative(0, -1, 0)
        }
        return current
    }
}
