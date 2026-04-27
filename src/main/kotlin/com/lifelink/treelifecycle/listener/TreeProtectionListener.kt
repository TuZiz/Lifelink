package com.lifelink.treelifecycle.listener

import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.i18n.MessageService
import com.lifelink.treelifecycle.service.TreeProtectionService
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent

class TreeProtectionListener(
    private val protectionService: TreeProtectionService,
    private val messageService: MessageService
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!protectionService.isProtectedSystemSapling(event.block)) return
        if (protectionService.canBypassSapling(event.player)) return

        event.isCancelled = true
        messageService.send(event.player, "protected-sapling")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (protectionService.canBypassSapling(event.player)) return
        val placed = event.blockPlaced
        val replaced = event.blockReplacedState.block
        if (protectionService.isProtectedSystemSapling(placed) || protectionService.isProtectedSystemSapling(replaced)) {
            event.isCancelled = true
            messageService.send(event.player, "protected-sapling")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockMultiPlace(event: BlockMultiPlaceEvent) {
        if (protectionService.canBypassSapling(event.player)) return
        if (event.replacedBlockStates.any { protectionService.isProtectedSystemSapling(it.block) }) {
            event.isCancelled = true
            messageService.send(event.player, "protected-sapling")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { touchesProtectedSapling(it, event.direction.modX, event.direction.modY, event.direction.modZ) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { touchesProtectedSapling(it, -event.direction.modX, -event.direction.modY, -event.direction.modZ) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (protectionService.isProtectedSystemSapling(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockFromTo(event: BlockFromToEvent) {
        if (protectionService.isProtectedSystemSapling(event.toBlock)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        if (protectionService.isProtectedSystemSapling(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (protectionService.isProtectedSystemSapling(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) {
        if (protectionService.isProtectedSystemSapling(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { protectionService.isProtectedSystemSapling(it) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { protectionService.isProtectedSystemSapling(it) }
    }

    private fun touchesProtectedSapling(block: Block, dx: Int, dy: Int, dz: Int): Boolean {
        val destination = block.getRelative(dx, dy, dz)
        return protectionService.isProtectedSystemSapling(block) ||
            protectionService.isProtectedSystemSapling(destination) ||
            protectionService.isProtectedSystemSapling(BlockLocation.from(destination))
    }
}
