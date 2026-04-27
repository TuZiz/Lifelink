package com.lifelink.treelifecycle.listener

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.ManagedTreeRecord
import com.lifelink.treelifecycle.domain.ReplantTaskStatus
import com.lifelink.treelifecycle.domain.TreeKind
import com.lifelink.treelifecycle.domain.TreeSnapshot
import com.lifelink.treelifecycle.i18n.MessageService
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import com.lifelink.treelifecycle.service.AdminSaplingModeService
import com.lifelink.treelifecycle.service.ReplantService
import com.lifelink.treelifecycle.service.TreeDetectionService
import com.lifelink.treelifecycle.service.TreeLifecycleService
import com.lifelink.treelifecycle.service.TreeProtectionService
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.StructureGrowEvent

class TreeLifecycleListener(
    private val configService: ConfigService,
    private val detectionService: TreeDetectionService,
    private val lifecycleService: TreeLifecycleService,
    private val protectionService: TreeProtectionService,
    private val replantService: ReplantService,
    private val messageService: MessageService,
    private val scheduler: SchedulerAdapter,
    private val adminSaplingModeService: AdminSaplingModeService
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!lifecycleService.isLoaded()) {
            event.isCancelled = true
            return
        }

        val blockLocation = BlockLocation.from(event.block)
        val systemSapling = lifecycleService.systemSaplingAt(blockLocation)
        if (systemSapling != null) {
            if (protectionService.canBypassSapling(event.player)) {
                lifecycleService.removeTree(systemSapling.id)
                return
            }
            event.isCancelled = true
            messageService.send(event.player, "protected-sapling")
            return
        }
        lifecycleService.playerSaplingAt(blockLocation)?.let {
            lifecycleService.removeTree(it.id)
            return
        }

        val logKind = TreeKind.fromLog(event.block.type) ?: return
        lifecycleService.playerTreeAt(blockLocation)?.let {
            lifecycleService.removeTreeIfRoot(it.id, blockLocation)
            return
        }
        val managedTree = lifecycleService.managedTreeAt(blockLocation)

        if (managedTree != null && configService.current().protection.protectManagedTrees && !protectionService.canBypassManagedTree(event.player)) {
            val root = managedRoot(managedTree)
            if (root == null || blockLocation != root || !isAllowedTool(event.player.inventory.itemInMainHand.type)) {
                event.isCancelled = true
                messageService.send(event.player, "managed-tree-illegal-break")
                return
            }
        }

        if (!isAllowedTool(event.player.inventory.itemInMainHand.type)) {
            return
        }
        if (event.player.isSneaking && configService.current().harvest.sneakBypassReplant) {
            return
        }

        val detected = detectionService.detectFromLog(event.block)
            ?: managedTree?.let { fallbackSnapshotFromManaged(it, event.block, logKind) }
            ?: return

        val safeSnapshot = foliaSafeSnapshot(detected) ?: return
        val beginResult = lifecycleService.beginReplantTask(safeSnapshot, event.player, managedTree)
        if (beginResult == TreeLifecycleService.BeginTaskResult.Busy) {
            return
        }

        val task = (beginResult as TreeLifecycleService.BeginTaskResult.Started).task
        lifecycleService.transitionTask(
            taskId = task.id,
            allowed = setOf(ReplantTaskStatus.RESERVED),
            next = ReplantTaskStatus.CUTTING_CONFIRMED
        )
        replantService.schedule(task.id, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!lifecycleService.isLoaded()) return
        val kind = TreeKind.fromSapling(event.blockPlaced.type) ?: return
        val location = BlockLocation.from(event.blockPlaced)
        if (adminSaplingModeService.shouldRecordAsManaged(event.player)) {
            lifecycleService.recordSystemSapling(kind, location)
            messageService.send(
                event.player,
                "sapling-mode-recorded",
                mapOf("tree_type" to messageService.treeTypeName(kind.key))
            )
            return
        }
        lifecycleService.recordPlayerSapling(kind, location)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        if (!lifecycleService.isLoaded()) return
        val saplingLocation = BlockLocation.from(event.location)
        val systemRecord = lifecycleService.systemSaplingAt(saplingLocation)
        val playerRecord = lifecycleService.playerSaplingAt(saplingLocation)
        val record = systemRecord ?: playerRecord ?: return

        val plannedLogs = event.blocks
            .filter { record.kind.isLog(it.type) }
            .map { BlockLocation.from(it) }
            .toSet()

        if (plannedLogs.isNotEmpty()) {
            if (systemRecord != null) {
                lifecycleService.promoteSaplingToManagedFromBlocks(saplingLocation, plannedLogs)
            } else {
                lifecycleService.promotePlayerSaplingToTreeFromBlocks(saplingLocation, plannedLogs)
            }
            return
        }

        // 少数服务端/插件组合不会在 StructureGrowEvent 暴露完整 BlockState；下一 tick 在同一区域确认真实方块。
        scheduler.runAtLater(event.world, saplingLocation.chunkX, saplingLocation.chunkZ, 1L) {
            detectionService.detectNear(event.location.block)?.let { snapshot ->
                if (systemRecord != null) {
                    lifecycleService.promoteSaplingToManaged(saplingLocation, snapshot)
                } else {
                    lifecycleService.promotePlayerSaplingToTree(saplingLocation, snapshot)
                }
            }
        }
    }

    private fun isAllowedTool(material: Material): Boolean =
        material in configService.current().harvest.allowedTools ||
            (material.isAir && configService.current().harvest.allowEmptyHand)

    private fun managedRoot(record: ManagedTreeRecord): BlockLocation? =
        record.logLocations.minWithOrNull(compareBy({ it.y }, { it.x }, { it.z }))

    private fun foliaSafeSnapshot(snapshot: TreeSnapshot): TreeSnapshot? {
        if (!scheduler.folia) return snapshot
        val chunks = snapshot.saplingLocations.map { it.chunkX to it.chunkZ }.toSet()
        if (chunks.size <= 1) return snapshot
        if (snapshot.kind.requiresFourSaplings) return null
        // Folia 下跨 chunk 的 2x2 补种需要多 region 协调；非强制 2x2 树种降级成单树苗补种。
        return snapshot.copy(saplingLocations = setOf(snapshot.root))
    }

    private fun fallbackSnapshotFromManaged(record: ManagedTreeRecord, block: Block, kind: TreeKind): TreeSnapshot? {
        val root = managedRoot(record) ?: return null
        val saplings = determineSaplings(kind, record.logLocations, root) ?: return null
        return TreeSnapshot(
            kind = kind,
            root = root,
            logLocations = record.logLocations,
            leafLocations = emptySet(),
            saplingLocations = saplings,
            detectedAtEpochMillis = System.currentTimeMillis()
        ).takeIf { block.world.uid == root.worldId }
    }

    private fun determineSaplings(kind: TreeKind, logs: Set<BlockLocation>, root: BlockLocation): Set<BlockLocation>? {
        val base = logs.filter { it.y == root.y }.toSet()
        val square = findTwoByTwo(base, root)
        return when {
            kind.requiresFourSaplings -> square
            kind.supportsFourSaplings && square != null -> square
            else -> setOf(root)
        }
    }

    private fun findTwoByTwo(base: Set<BlockLocation>, root: BlockLocation): Set<BlockLocation>? {
        val candidates = listOf(root, root.relative(-1, 0, 0), root.relative(0, 0, -1), root.relative(-1, 0, -1))
        return candidates.asSequence()
            .map { start -> setOf(start, start.relative(1, 0, 0), start.relative(0, 0, 1), start.relative(1, 0, 1)) }
            .firstOrNull { square -> square.all { it in base } }
    }
}
