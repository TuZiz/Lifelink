package com.lifelink.treelifecycle.wilderness

import com.lifelink.treelifecycle.config.WildernessConfig
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.TileState
import org.bukkit.inventory.InventoryHolder
import kotlin.math.max
import kotlin.math.min

class OriginWorldService(
    private val configProvider: () -> WildernessConfig
) {
    fun originWorld(): World? = Bukkit.getWorld(configProvider().originWorld.name)
    fun originWorldName(): String = configProvider().originWorld.name
}

class BuildingScanner(
    private val configProvider: () -> WildernessConfig,
    private val assetIndexService: AssetIndexService,
    private val manualProtectionService: ManualProtectionService,
    private val originWorldService: OriginWorldService
) {
    fun scanBox(world: World, box: BlockBox, surfaceOnly: Boolean): AreaScanReport {
        val chunks = box.chunks().map { (chunkX, chunkZ) -> scanChunk(world, chunkX, chunkZ, box, surfaceOnly) }
        val mask = ProtectionMaskService(configProvider, assetIndexService, manualProtectionService).buildMask(box, chunks)
        return AreaScanReport(world.uid, world.name, box, chunks, mask)
    }

    fun scanChunk(world: World, chunkX: Int, chunkZ: Int, limitBox: BlockBox? = null, surfaceOnly: Boolean = false): ChunkRiskReport {
        val config = configProvider()
        val chunkMinX = chunkX shl 4
        val chunkMinZ = chunkZ shl 4
        val chunkMaxX = chunkMinX + 15
        val chunkMaxZ = chunkMinZ + 15
        val minX = max(limitBox?.minX ?: chunkMinX, chunkMinX)
        val maxX = min(limitBox?.maxX ?: chunkMaxX, chunkMaxX)
        val minZ = max(limitBox?.minZ ?: chunkMinZ, chunkMinZ)
        val maxZ = min(limitBox?.maxZ ?: chunkMaxZ, chunkMaxZ)
        var buildScore = 0
        var damageScore = 0
        var recoverableBlocks = 0
        var protectedBlocks = 0
        var skippedBlocks = 0
        val reasons = linkedSetOf<String>()
        val detectedAssets = mutableListOf<DetectedAsset>()
        val originAvailable = originWorldService.originWorld() != null
        val chunkAssets = assetIndexService.assetsInChunk(world.uid, chunkX, chunkZ)
        if (chunkAssets.isNotEmpty()) {
            reasons += "asset-index:${chunkAssets.size}"
            protectedBlocks += chunkAssets.size
        }
        val manual = manualProtectionService.areasIn(
            BlockBox(world.uid, world.name, chunkMinX, world.minHeight, chunkMinZ, chunkMaxX, world.maxHeight - 1, chunkMaxZ)
        )
        if (manual.isNotEmpty()) {
            reasons += "manual-protection:${manual.size}"
            protectedBlocks += manual.size
        }

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val surfaceY = world.getHighestBlockYAt(x, z)
                val minY = if (surfaceOnly) {
                    max(world.minHeight, surfaceY + config.scanner.surfaceRestoreMinOffset)
                } else {
                    max(world.minHeight, limitBox?.minY ?: world.minHeight)
                }
                val maxY = if (surfaceOnly) {
                    min(world.maxHeight - 1, surfaceY + config.scanner.surfaceRestoreMaxOffset)
                } else {
                    min(world.maxHeight - 1, limitBox?.maxY ?: (world.maxHeight - 1))
                }
                for (y in minY..maxY) {
                    val block = world.getBlockAt(x, y, z)
                    val score = WildernessMaterials.buildScore(block.type)
                    if (score > 0) buildScore += score
                    when (WildernessMaterials.assetType(block.type)) {
                        AssetType.CONTAINER -> {
                            reasons += "container"
                            detectedAssets += DetectedAsset(world.uid, world.name, x, y, z, AssetType.CONTAINER)
                            protectedBlocks++
                        }
                        AssetType.BED -> {
                            reasons += "bed"
                            detectedAssets += DetectedAsset(world.uid, world.name, x, y, z, AssetType.BED)
                            protectedBlocks++
                        }
                        AssetType.SIGN -> {
                            reasons += "sign"
                            detectedAssets += DetectedAsset(world.uid, world.name, x, y, z, AssetType.SIGN)
                            protectedBlocks++
                        }
                        AssetType.REDSTONE -> {
                            reasons += "redstone"
                            detectedAssets += DetectedAsset(world.uid, world.name, x, y, z, AssetType.REDSTONE)
                            protectedBlocks++
                        }
                        else -> Unit
                    }
                    if (isTileOrInventory(block)) {
                        reasons += "tile-state"
                        detectedAssets += DetectedAsset(world.uid, world.name, x, y, z, AssetType.UNKNOWN)
                        protectedBlocks++
                    }
                    if (WildernessMaterials.isRecoverableNaturalDamage(block.type) && !WildernessMaterials.isHighValueAsset(block.type)) {
                        damageScore++
                        if (originAvailable) recoverableBlocks++
                    }
                }
            }
        }

        if (buildScore > 0) reasons += "building-score:$buildScore"
        val risk = classifyRisk(buildScore, reasons, config)
        if (risk != RiskLevel.LOW) skippedBlocks += protectedBlocks
        return ChunkRiskReport(
            worldId = world.uid,
            worldName = world.name,
            chunkX = chunkX,
            chunkZ = chunkZ,
            risk = risk,
            buildScore = buildScore,
            damageScore = damageScore,
            assetCount = chunkAssets.size,
            recoverableBlocks = recoverableBlocks,
            protectedBlocks = protectedBlocks,
            skippedBlocks = skippedBlocks,
            reasons = reasons.toList(),
            detectedAssets = detectedAssets
        )
    }

    private fun classifyRisk(buildScore: Int, reasons: Set<String>, config: WildernessConfig): RiskLevel = when {
        reasons.any { it == "container" || it == "bed" || it == "redstone" || it == "tile-state" } -> RiskLevel.HIGH
        reasons.any { it == "sign" || it.startsWith("manual-protection") || it.startsWith("asset-index") } -> RiskLevel.MEDIUM
        buildScore >= config.scanner.highRiskMinBuildScore -> RiskLevel.MEDIUM
        buildScore >= config.scanner.lowRiskMaxBuildScore -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    private fun isTileOrInventory(block: Block): Boolean {
        val state = block.state
        return state is Container || state is InventoryHolder || state is TileState
    }
}

class ProtectionMaskService(
    private val configProvider: () -> WildernessConfig,
    private val assetIndexService: AssetIndexService,
    private val manualProtectionService: ManualProtectionService
) {
    fun buildMask(box: BlockBox, reports: List<ChunkRiskReport> = emptyList()): ProtectionMask {
        val config = configProvider().protection
        val regions = mutableListOf<ProtectionRegion>()
        manualProtectionService.areasIn(box).forEach { area ->
            regions += ProtectionRegion(area.box.expand(config.manualPaddingXz, config.manualPaddingY), "manual:${area.name}")
        }
        assetIndexService.assetsIn(box).forEach { asset ->
            val padding = when (asset.assetType) {
                AssetType.CONTAINER -> config.containerPaddingXz to config.containerPaddingY
                AssetType.BED -> config.bedPaddingXz to config.bedPaddingY
                AssetType.SIGN -> config.signPaddingXz to config.signPaddingY
                AssetType.REDSTONE -> config.redstonePaddingXz to config.redstonePaddingY
                AssetType.HANGING_ENTITY -> config.signPaddingXz to config.signPaddingY
                else -> config.buildingPaddingXz to config.buildingPaddingY
            }
            val assetBox = BlockBox(asset.worldId, asset.worldName, asset.x, asset.y, asset.z, asset.x, asset.y, asset.z)
            regions += ProtectionRegion(assetBox.expand(padding.first, padding.second), "asset:${asset.assetType.name.lowercase()}")
        }
        reports.filter { it.requiresWholeChunkProtection() }.forEach { report ->
            val chunkBox = BlockBox(
                report.worldId,
                report.worldName,
                report.chunkX shl 4,
                box.minY,
                report.chunkZ shl 4,
                (report.chunkX shl 4) + 15,
                box.maxY,
                (report.chunkZ shl 4) + 15
            )
            regions += ProtectionRegion(chunkBox, "risk:${report.risk.name.lowercase()}")
        }
        reports.flatMap { it.detectedAssets }.forEach { asset ->
            val padding = when (asset.assetType) {
                AssetType.CONTAINER -> config.containerPaddingXz to config.containerPaddingY
                AssetType.BED -> config.bedPaddingXz to config.bedPaddingY
                AssetType.SIGN -> config.signPaddingXz to config.signPaddingY
                AssetType.REDSTONE -> config.redstonePaddingXz to config.redstonePaddingY
                else -> config.buildingPaddingXz to config.buildingPaddingY
            }
            val assetBox = BlockBox(asset.worldId, asset.worldName, asset.x, asset.y, asset.z, asset.x, asset.y, asset.z)
            regions += ProtectionRegion(assetBox.expand(padding.first, padding.second), "scan:${asset.assetType.name.lowercase()}")
        }
        return ProtectionMask(regions)
    }

    private fun ChunkRiskReport.requiresWholeChunkProtection(): Boolean {
        if (risk != RiskLevel.HIGH || detectedAssets.isNotEmpty()) return false
        return reasons.any {
            it == "container" ||
                it == "bed" ||
                it == "sign" ||
                it == "redstone" ||
                it == "tile-state"
        }
    }
}

class PreviewService(
    private val configProvider: () -> WildernessConfig
) {
    fun show(world: World, report: AreaScanReport) {
        if (!configProvider().preview.particleEnabled) return
        report.chunks.forEach { chunk ->
            val color = when (chunk.risk) {
                RiskLevel.LOW -> Color.fromRGB(34, 197, 94)
                RiskLevel.MEDIUM -> Color.fromRGB(250, 204, 21)
                RiskLevel.HIGH -> Color.fromRGB(239, 68, 68)
            }
            val dust = Particle.DustOptions(color, 2.4f)
            val startX = chunk.chunkX shl 4
            val startZ = chunk.chunkZ shl 4
            val border = listOf(0, 4, 8, 12, 15)
            border.forEach { offset ->
                spawnDust(world, dust, startX + offset, startZ, 18)
                spawnDust(world, dust, startX + offset, startZ + 15, 18)
                spawnDust(world, dust, startX, startZ + offset, 18)
                spawnDust(world, dust, startX + 15, startZ + offset, 18)
            }
            for (dx in listOf(2, 5, 8, 11, 14)) {
                for (dz in listOf(2, 5, 8, 11, 14)) {
                    spawnDust(world, dust, startX + dx, startZ + dz, 10)
                }
            }
            val markerX = startX + 8
            val markerZ = startZ + 8
            val markerY = world.getHighestBlockYAt(markerX, markerZ).coerceAtLeast(world.minHeight) + 1
            for (height in 0..5) {
                world.spawnParticle(
                    Particle.DUST,
                    markerX + 0.5,
                    markerY + 0.5 + height,
                    markerZ + 0.5,
                    20,
                    0.35,
                    0.35,
                    0.35,
                    dust
                )
            }
        }
    }

    private fun spawnDust(world: World, dust: Particle.DustOptions, x: Int, z: Int, count: Int) {
        val y = world.getHighestBlockYAt(x, z).coerceAtLeast(world.minHeight) + 1
        world.spawnParticle(Particle.DUST, x + 0.5, y + 0.35, z + 0.5, count, 0.35, 0.25, 0.35, dust)
    }
}
