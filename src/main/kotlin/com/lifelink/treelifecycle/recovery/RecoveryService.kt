package com.lifelink.treelifecycle.recovery

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.LifecycleState
import com.lifelink.treelifecycle.domain.ManagedTreeRecord
import com.lifelink.treelifecycle.domain.ReplantTaskRecord
import com.lifelink.treelifecycle.domain.ReplantTaskStatus
import com.lifelink.treelifecycle.domain.TreeKind
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import com.lifelink.treelifecycle.service.ReplantService
import com.lifelink.treelifecycle.service.TreeDetectionService
import com.lifelink.treelifecycle.service.TreeLifecycleService
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

class RecoveryService(
    private val configService: ConfigService,
    private val lifecycleService: TreeLifecycleService,
    private val detectionService: TreeDetectionService,
    private val replantService: ReplantService,
    private val scheduler: SchedulerAdapter,
    private val logger: Logger
) {
    fun recoverStartup(): Int {
        if (!configService.current().replant.recoveryOnStartup) return 0
        return recoverNow()
    }

    fun recoverNow(): Int {
        val queued = AtomicInteger(0)
        cleanupOldTerminalTasks()

        lifecycleService.activeTasks().forEach { task ->
            replantService.schedule(task.id, configService.current().replant.retryDelayTicks)
            queued.incrementAndGet()
        }

        lifecycleService.allTrees().forEach { record ->
            scheduler.runGlobal {
                val world = resolveWorld(record.primaryLocation())
                if (world == null) {
                    logger.warning("Skipping recovery for ${record.id}: world ${record.primaryLocation().worldName} is not loaded")
                    return@runGlobal
                }
                val primary = record.primaryLocation()
                scheduler.runAt(world, primary.chunkX, primary.chunkZ) {
                    recoverRecord(record, world)
                }
            }
            queued.incrementAndGet()
        }

        return queued.get()
    }

    private fun recoverRecord(record: ManagedTreeRecord, world: World) {
        try {
            when (record.lifecycleState) {
                LifecycleState.SYSTEM_PLANTED_SAPLING -> recoverSapling(record, world)
                LifecycleState.SYSTEM_MANAGED_TREE -> recoverManagedTree(record, world)
                LifecycleState.NATURAL_TREE,
                LifecycleState.PLAYER_PLANTED_SAPLING,
                LifecycleState.PLAYER_PLANTED_TREE -> Unit
            }
        } catch (error: Throwable) {
            logger.log(Level.SEVERE, "Recovery failed for lifecycle ${record.id}", error)
        }
    }

    private fun recoverSapling(record: ManagedTreeRecord, world: World) {
        if (!record.saplingLocations.all { world.isChunkLoaded(it.chunkX, it.chunkZ) }) return
        val materials = record.saplingLocations.map { world.getBlockAt(it.x, it.y, it.z).type }
        if (materials.all { it == record.kind.saplingMaterial }) return

        val primary = record.primaryLocation()
        val detected = detectionService.detectNear(world.getBlockAt(primary.x, primary.y, primary.z))
        if (detected != null && detected.kind == record.kind) {
            lifecycleService.promoteSaplingToManaged(primary, detected)
            return
        }

        val task = recoveryTask(record, primary, record.saplingLocations)
        if (lifecycleService.registerRecoveryTask(task)) {
            replantService.schedule(task.id, configService.current().replant.retryDelayTicks)
        }
    }

    private fun recoverManagedTree(record: ManagedTreeRecord, world: World) {
        val root = managedRoot(record) ?: return
        if (!world.isChunkLoaded(root.chunkX, root.chunkZ)) return
        val rootMaterial = world.getBlockAt(root.x, root.y, root.z).type
        if (record.kind.isLog(rootMaterial)) return

        val saplings = determineSaplings(record.kind, record.logLocations, root) ?: setOf(root)
        val task = recoveryTask(record, root, saplings)
        lifecycleService.removeTree(record.id)
        if (lifecycleService.registerRecoveryTask(task)) {
            replantService.schedule(task.id, configService.current().replant.retryDelayTicks)
        }
    }

    private fun cleanupOldTerminalTasks() {
        val now = System.currentTimeMillis()
        val retentionMillis = configService.current().persistence.terminalTaskRetentionSeconds * 1000
        lifecycleService.allTasks()
            .filter { it.status.isTerminal() && now - it.updatedAtEpochMillis > retentionMillis }
            .forEach { lifecycleService.removeTask(it.id) }
    }

    private fun recoveryTask(record: ManagedTreeRecord, root: BlockLocation, saplings: Set<BlockLocation>): ReplantTaskRecord {
        val now = System.currentTimeMillis()
        return ReplantTaskRecord(
            id = UUID.randomUUID(),
            lifecycleId = record.id,
            kind = record.kind,
            rootLocation = root,
            saplingLocations = saplings,
            status = ReplantTaskStatus.REPLANT_PENDING,
            attempts = 0,
            playerUuid = null,
            playerName = null,
            failureReason = "recovery",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            version = 0
        )
    }

    private fun managedRoot(record: ManagedTreeRecord): BlockLocation? =
        record.logLocations.minWithOrNull(compareBy({ it.y }, { it.x }, { it.z }))

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

    private fun resolveWorld(location: BlockLocation): World? =
        Bukkit.getWorld(location.worldId) ?: Bukkit.getWorld(location.worldName)
}
