package com.lifelink.treelifecycle.wilderness

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.TileState
import org.bukkit.inventory.InventoryHolder

object WildernessMaterials {
    private val containerNames = setOf(
        "CHEST",
        "TRAPPED_CHEST",
        "BARREL",
        "FURNACE",
        "BLAST_FURNACE",
        "SMOKER",
        "HOPPER",
        "DROPPER",
        "DISPENSER",
        "BREWING_STAND",
        "SHULKER_BOX"
    )

    private val workstationNames = setOf(
        "LECTERN",
        "DECORATED_POT",
        "ENCHANTING_TABLE",
        "ANVIL",
        "CHIPPED_ANVIL",
        "DAMAGED_ANVIL",
        "BEACON",
        "CRAFTING_TABLE",
        "CARTOGRAPHY_TABLE",
        "FLETCHING_TABLE",
        "SMITHING_TABLE",
        "GRINDSTONE",
        "LOOM",
        "STONECUTTER"
    )

    private val redstoneNames = setOf(
        "REDSTONE_WIRE",
        "REPEATER",
        "COMPARATOR",
        "PISTON",
        "STICKY_PISTON",
        "OBSERVER",
        "NOTE_BLOCK",
        "JUKEBOX",
        "TARGET",
        "LEVER",
        "DAYLIGHT_DETECTOR",
        "REDSTONE_TORCH",
        "REDSTONE_WALL_TORCH",
        "REDSTONE_LAMP",
        "TRIPWIRE",
        "TRIPWIRE_HOOK"
    )

    fun assetType(material: Material): AssetType? = when {
        isContainerMaterial(material) -> AssetType.CONTAINER
        isBed(material) -> AssetType.BED
        isSign(material) -> AssetType.SIGN
        isRedstone(material) -> AssetType.REDSTONE
        isFarm(material) -> AssetType.FARM
        isBuildingMaterial(material) -> AssetType.BUILDING_BLOCK
        else -> null
    }

    fun buildScore(material: Material): Int = when {
        isContainerMaterial(material) -> 30
        isBed(material) -> 30
        isSign(material) -> 20
        isHangingItem(material) -> 20
        isRedstone(material) -> 15
        material.name in workstationNames -> 10
        material.name.endsWith("_DOOR") || material.name.endsWith("_TRAPDOOR") -> 8
        material.name.contains("GLASS") || material.name.endsWith("LANTERN") || material.name.endsWith("TORCH") -> 5
        material.name.endsWith("_STAIRS") || material.name.endsWith("_SLAB") -> 3
        isBuildingMaterial(material) -> 1
        else -> 0
    }

    fun isHighValueAsset(material: Material): Boolean =
        isContainerMaterial(material) || isBed(material) || isRedstone(material) || isSign(material) || isHangingItem(material)

    fun isHardDenyBlock(block: Block): Boolean {
        val state = block.state
        return isHighValueAsset(block.type) || state is Container || state is InventoryHolder || state is Sign || state is TileState
    }

    fun isContainerMaterial(material: Material): Boolean =
        material.name in containerNames || material.name.endsWith("SHULKER_BOX")

    fun isBed(material: Material): Boolean = material.name.endsWith("_BED")

    fun isSign(material: Material): Boolean = material.name.endsWith("_SIGN") || material.name.endsWith("_WALL_SIGN") ||
        material.name.endsWith("_HANGING_SIGN") || material.name.endsWith("_WALL_HANGING_SIGN")

    fun isRedstone(material: Material): Boolean = material.name in redstoneNames || material.name.contains("REDSTONE")

    fun isHangingItem(material: Material): Boolean = material.name == "ITEM_FRAME" || material.name == "GLOW_ITEM_FRAME" || material.name == "ARMOR_STAND"

    fun isFarm(material: Material): Boolean = material == Material.FARMLAND || material.name.endsWith("_CROP") ||
        material.name in setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "COCOA", "MELON_STEM", "PUMPKIN_STEM")

    fun isBuildingMaterial(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_PLANKS") ||
            name.endsWith("_STAIRS") ||
            name.endsWith("_SLAB") ||
            name.endsWith("_FENCE") ||
            name.endsWith("_FENCE_GATE") ||
            name.endsWith("_WALL") ||
            name.endsWith("_DOOR") ||
            name.endsWith("_TRAPDOOR") ||
            name.contains("GLASS") ||
            name.endsWith("LANTERN") ||
            name.endsWith("TORCH") ||
            name.endsWith("CONCRETE") ||
            name.endsWith("TERRACOTTA") ||
            name.contains("BRICKS") ||
            name.endsWith("WOOL") ||
            name.endsWith("CARPET") ||
            name == "IRON_BARS" ||
            name == "CHAIN"
    }

    fun isRecoverableNaturalDamage(material: Material): Boolean =
        material.isAir || material == Material.WATER || material == Material.LAVA || material == Material.FIRE ||
            material == Material.COBBLESTONE || material == Material.DIRT || material == Material.COARSE_DIRT
}
