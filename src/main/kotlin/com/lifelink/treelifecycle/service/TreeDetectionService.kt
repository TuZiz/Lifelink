package com.lifelink.treelifecycle.service

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.config.DetectionConfig
import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.TreeKind
import com.lifelink.treelifecycle.domain.TreeSnapshot
import org.bukkit.Material
import org.bukkit.block.Block
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

class TreeDetectionService(
    private val configService: ConfigService
) {
    private val directions = arrayOf(
        intArrayOf(1, 0, 0),
        intArrayOf(-1, 0, 0),
        intArrayOf(0, 1, 0),
        intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1),
        intArrayOf(0, 0, -1)
    )

    fun detectFromLog(block: Block): TreeSnapshot? {
        val kind = TreeKind.fromLog(block.type) ?: return null
        val config = configService.current().detection
        val origin = BlockLocation.from(block)
        val logs = scanLogs(block, kind, origin, config)
        if (logs.size < config.minLogNodes || logs.size > config.maxLogNodes) return null

        val minY = logs.minOf { it.y }
        val maxY = logs.maxOf { it.y }
        if (maxY - minY + 1 < 3) return null

        val root = logs.filter { it.y == minY }.minWithOrNull(compareBy({ it.x }, { it.z })) ?: return null
        val baseLogs = logs.filter { it.y == minY }
        if (baseLogs.size > 4) return null
        if (!hasPlantableBase(block, root)) return null

        val saplingPlan = determineSaplingPlan(kind, logs, root) ?: return null
        if (!passesTrunkCoreCheck(kind, logs, saplingPlan, config)) return null

        val leaves = scanLeaves(block, kind, logs, config)
        val requiredLeaves = max(config.minLeafNodes, (logs.size * config.minLeafToLogRatio).toInt())
        if (leaves.size < requiredLeaves) return null
        if (leaves.map { it.y }.distinct().size < config.minLeafLayers) return null
        if (leaves.none { it.y > minY + 1 }) return null

        return TreeSnapshot(
            kind = kind,
            root = root,
            logLocations = logs,
            leafLocations = leaves,
            saplingLocations = saplingPlan,
            detectedAtEpochMillis = System.currentTimeMillis()
        )
    }

    fun detectNear(locationBlock: Block): TreeSnapshot? {
        TreeKind.fromLog(locationBlock.type)?.let { return detectFromLog(locationBlock) }
        for (dx in -1..1) {
            for (dz in -1..1) {
                for (dy in 0..2) {
                    val candidate = locationBlock.getRelative(dx, dy, dz)
                    if (TreeKind.fromLog(candidate.type) != null) {
                        detectFromLog(candidate)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun scanLogs(
        start: Block,
        kind: TreeKind,
        origin: BlockLocation,
        config: DetectionConfig
    ): Set<BlockLocation> {
        val queue = ArrayDeque<Block>()
        val visited = linkedSetOf<BlockLocation>()
        queue.add(start)

        while (queue.isNotEmpty() && visited.size <= config.maxLogNodes) {
            val block = queue.removeFirst()
            val location = BlockLocation.from(block)
            if (location in visited) continue
            if (!kind.isLog(block.type)) continue
            if (!withinDetectionWindow(origin, location, config)) continue

            visited += location
            directions.forEach { direction ->
                queue.add(block.getRelative(direction[0], direction[1], direction[2]))
            }
        }
        return if (visited.size > config.maxLogNodes) emptySet() else visited
    }

    private fun withinDetectionWindow(origin: BlockLocation, candidate: BlockLocation, config: DetectionConfig): Boolean {
        if (origin.worldId != candidate.worldId) return false
        if (abs(origin.y - candidate.y) > config.maxTreeHeight) return false
        val maxDistance = config.maxHorizontalDistance
        return candidate.horizontalDistanceSquared(origin) <= maxDistance * maxDistance
    }

    private fun scanLeaves(
        anyBlockInWorld: Block,
        kind: TreeKind,
        logs: Set<BlockLocation>,
        config: DetectionConfig
    ): Set<BlockLocation> {
        val world = anyBlockInWorld.world
        val minY = logs.minOf { it.y }
        val maxY = logs.maxOf { it.y }
        val upperLogs = logs.filter { it.y >= minY + ((maxY - minY) / 2) }
        val leaves = linkedSetOf<BlockLocation>()
        val visited = hashSetOf<BlockLocation>()
        var scanned = 0

        for (log in upperLogs) {
            for (dx in -config.canopyPadding..config.canopyPadding) {
                for (dy in -1..config.canopyPadding) {
                    for (dz in -config.canopyPadding..config.canopyPadding) {
                        if (scanned >= config.maxLeafScanBlocks) return leaves
                        val candidateLocation = log.relative(dx, dy, dz)
                        if (!visited.add(candidateLocation)) continue
                        scanned++
                        if (candidateLocation.y < world.minHeight || candidateLocation.y >= world.maxHeight) continue
                        val material = world.getBlockAt(candidateLocation.x, candidateLocation.y, candidateLocation.z).type
                        if (kind.isLeaf(material)) leaves += candidateLocation
                    }
                }
            }
        }
        return leaves
    }

    private fun hasPlantableBase(anyBlockInWorld: Block, root: BlockLocation): Boolean {
        val below = anyBlockInWorld.world.getBlockAt(root.x, root.y - 1, root.z).type
        return below in plantableSoils
    }

    private fun determineSaplingPlan(kind: TreeKind, logs: Set<BlockLocation>, root: BlockLocation): Set<BlockLocation>? {
        val base = logs.filter { it.y == root.y }.toSet()
        val square = findTwoByTwo(base, root)
        return when {
            kind.requiresFourSaplings -> square
            kind.supportsFourSaplings && square != null -> square
            else -> setOf(root)
        }
    }

    private fun passesTrunkCoreCheck(
        kind: TreeKind,
        logs: Set<BlockLocation>,
        saplingPlan: Set<BlockLocation>,
        config: DetectionConfig
    ): Boolean {
        val requiredRatio = minTrunkCoreRatio(kind, config)
        if (requiredRatio <= 0.0) return true
        val coreColumns = saplingPlan.map { it.x to it.z }.toSet()
        val coreLogs = logs.count { (it.x to it.z) in coreColumns }
        return coreLogs.toDouble() / logs.size.toDouble() >= requiredRatio
    }

    private fun minTrunkCoreRatio(kind: TreeKind, config: DetectionConfig): Double {
        return when (kind) {
            TreeKind.CHERRY -> config.minTrunkCoreRatio.coerceAtMost(CHERRY_MAX_TRUNK_CORE_RATIO)
            else -> config.minTrunkCoreRatio
        }
    }

    private fun findTwoByTwo(base: Set<BlockLocation>, root: BlockLocation): Set<BlockLocation>? {
        val candidates = listOf(
            root,
            root.relative(-1, 0, 0),
            root.relative(0, 0, -1),
            root.relative(-1, 0, -1)
        )
        return candidates.asSequence()
            .map { start ->
                setOf(start, start.relative(1, 0, 0), start.relative(0, 0, 1), start.relative(1, 0, 1))
            }
            .firstOrNull { square -> square.all { it in base } }
    }

    companion object {
        private const val CHERRY_MAX_TRUNK_CORE_RATIO = 0.20

        val plantableSoils: Set<Material> = setOf(
            Material.GRASS_BLOCK,
            Material.DIRT,
            Material.COARSE_DIRT,
            Material.PODZOL,
            Material.ROOTED_DIRT,
            Material.MYCELIUM,
            Material.MOSS_BLOCK,
            Material.MUD,
            Material.FARMLAND
        )
    }
}
