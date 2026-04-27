package com.lifelink.treelifecycle.wilderness

import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Hanging
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.InventoryHolder

class AssetEventListener(
    private val assetIndexService: AssetIndexService
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val type = WildernessMaterials.assetType(event.blockPlaced.type) ?: return
        assetIndexService.record(event.blockPlaced, type, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockMultiPlace(event: BlockMultiPlaceEvent) {
        event.replacedBlockStates.forEach { state ->
            val type = WildernessMaterials.assetType(state.type) ?: return@forEach
            assetIndexService.record(state.block, type, event.player, state.type)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (WildernessMaterials.isHighValueAsset(event.block.type)) {
            assetIndexService.record(event.block, WildernessMaterials.assetType(event.block.type) ?: AssetType.UNKNOWN, event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        assetIndexService.record(event.blockClicked.getRelative(event.blockFace), AssetType.BUCKET_USE, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        assetIndexService.record(event.blockClicked, AssetType.BUCKET_USE, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val block = event.entity.location.block
        assetIndexService.record(block, AssetType.HANGING_ENTITY, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player
        assetIndexService.record(event.entity.location.block, AssetType.HANGING_ENTITY, player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val holder = event.inventory.holder as? InventoryHolder ?: return
        val location = when (holder) {
            is Block -> holder.location
            else -> event.player.location
        }
        assetIndexService.recordActivity(location, event.player as? Player, AssetType.CONTAINER)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        assetIndexService.record(event.block, AssetType.SIGN, event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (block.state is InventoryHolder || WildernessMaterials.isHighValueAsset(block.type)) {
            assetIndexService.record(block, WildernessMaterials.assetType(block.type) ?: AssetType.FREQUENT_ACTIVITY, event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val entity = event.entity
        if (entity is ArmorStand || entity is Hanging) {
            assetIndexService.record(entity.location.block, AssetType.HANGING_ENTITY, event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val player = event.player ?: return
        assetIndexService.record(event.block, AssetType.FARM, player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        if (WildernessMaterials.isFarm(event.newState.type)) {
            assetIndexService.record(event.block, AssetType.FARM, null, event.newState.type)
        }
    }
}
