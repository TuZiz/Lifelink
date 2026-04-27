package com.lifelink.treelifecycle.service

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.FailureStrategy
import com.lifelink.treelifecycle.domain.ReplantTaskRecord
import com.lifelink.treelifecycle.domain.ReplantTaskStatus
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class ReplantService(
    private val configService: ConfigService,
    private val lifecycleService: TreeLifecycleService,
    private val scheduler: SchedulerAdapter,
    private val logger: Logger
) {
    private val runningAttempts = ConcurrentHashMap.newKeySet<UUID>()

    fun schedule(task: ReplantTaskRecord, delayTicks: Long) {
        schedule(task.id, delayTicks)
    }

    fun schedule(taskId: UUID, delayTicks: Long) {
        val task = lifecycleService.task(taskId) ?: return
        if (task.status.isTerminal()) return

        lifecycleService.transitionTask(
            taskId = taskId,
            allowed = setOf(
                ReplantTaskStatus.RESERVED,
                ReplantTaskStatus.CUTTING_CONFIRMED,
                ReplantTaskStatus.REPLANT_PENDING,
                ReplantTaskStatus.REPLANT_SCHEDULED
            ),
            next = ReplantTaskStatus.REPLANT_SCHEDULED,
            reason = task.failureReason
        )

        scheduler.runGlobal {
            val current = lifecycleService.task(taskId) ?: return@runGlobal
            val world = resolveWorld(current.rootLocation)
            if (world == null) {
                handleFailure(current, "world-unavailable", fromRegionThread = false)
                return@runGlobal
            }
            scheduler.runAtLater(world, current.rootLocation.chunkX, current.rootLocation.chunkZ, delayTicks) {
                attempt(taskId)
            }
        }
    }

    fun attempt(taskId: UUID) {
        if (!runningAttempts.add(taskId)) return
        try {
            val task = lifecycleService.transitionTask(
                taskId = taskId,
                allowed = setOf(
                    ReplantTaskStatus.RESERVED,
                    ReplantTaskStatus.CUTTING_CONFIRMED,
                    ReplantTaskStatus.REPLANT_PENDING,
                    ReplantTaskStatus.REPLANT_SCHEDULED
                ),
                next = ReplantTaskStatus.REPLANT_PENDING
            ) ?: lifecycleService.task(taskId) ?: return

            if (task.status.isTerminal()) return
            val world = resolveWorld(task.rootLocation)
            if (world == null) {
                handleFailure(task, "world-unavailable", fromRegionThread = true)
                return
            }

            val validation = validate(task, world)
            if (validation != null) {
                handleFailure(task, validation, fromRegionThread = true)
                return
            }

            task.saplingLocations.forEach { location ->
                world.getBlockAt(location.x, location.y, location.z).type = task.kind.saplingMaterial
            }
            spawnPlantParticles(world, task)

            lifecycleService.recordSystemSapling(task)
            val completed = task.transition(ReplantTaskStatus.REPLANTED, System.currentTimeMillis(), null)
            lifecycleService.saveTask(completed)
            lifecycleService.completeTask(completed, ReplantTaskStatus.REPLANTED)
        } catch (error: Throwable) {
            logger.log(Level.SEVERE, "Unexpected error while attempting tree replant", error)
        } finally {
            runningAttempts.remove(taskId)
        }
    }

    private fun validate(task: ReplantTaskRecord, world: World): String? {
        val chunks = task.saplingLocations.map { it.chunkX to it.chunkZ }.toSet()
        if (scheduler.folia && chunks.size > 1) {
            return "sapling-plan-crosses-folia-region-boundary"
        }

        val locationsToCheck = task.saplingLocations + task.rootLocation
        val unloaded = locationsToCheck.any { !world.isChunkLoaded(it.chunkX, it.chunkZ) }
        if (unloaded) return "chunk-unloaded"

        for (location in task.saplingLocations) {
            if (location.y <= world.minHeight || location.y >= world.maxHeight) return "invalid-height"
            val target = world.getBlockAt(location.x, location.y, location.z)
            if (task.kind.isLog(target.type)) return ROOT_STILL_PRESENT
            if (!target.type.isAir && target.type != task.kind.saplingMaterial) return "target-not-empty"
            val below = world.getBlockAt(location.x, location.y - 1, location.z).type
            if (below !in TreeDetectionService.plantableSoils) return "invalid-soil"

            val requiredClearHeight = configService.current().replant.requiredClearHeight
            if (requiredClearHeight > 0) {
                for (offset in 1..requiredClearHeight) {
                    val y = location.y + offset
                    if (y >= world.maxHeight) return "insufficient-growth-space"
                    val material = world.getBlockAt(location.x, y, location.z).type
                    if (!material.isAir && material != Material.CAVE_AIR && material != Material.VOID_AIR) {
                        return "insufficient-growth-space"
                    }
                }
            }
        }
        return null
    }

    private fun handleFailure(task: ReplantTaskRecord, reason: String, fromRegionThread: Boolean) {
        val config = configService.current().replant
        if (reason == ROOT_STILL_PRESENT) {
            if (System.currentTimeMillis() - task.createdAtEpochMillis <= config.rootWaitTimeoutSeconds * 1000) {
                val updated = task.transition(
                    next = ReplantTaskStatus.REPLANT_SCHEDULED,
                    now = System.currentTimeMillis(),
                    reason = reason,
                    attempts = task.attempts
                )
                lifecycleService.saveTask(updated)
                schedule(updated, config.rootWaitRetryDelayTicks)
            } else {
                lifecycleService.completeTask(task, ReplantTaskStatus.FAILED, reason)
            }
            return
        }

        when (config.failureStrategy) {
            FailureStrategy.CANCEL -> {
                lifecycleService.completeTask(task, ReplantTaskStatus.ROLLED_BACK, reason)
            }
            FailureStrategy.RETRY -> {
                if (task.attempts < config.maxRetryAttempts) {
                    val updated = task.transition(
                        next = ReplantTaskStatus.REPLANT_SCHEDULED,
                        now = System.currentTimeMillis(),
                        reason = reason,
                        attempts = task.attempts + 1
                    )
                    lifecycleService.saveTask(updated)
                    schedule(updated, config.retryDelayTicks)
                } else {
                    lifecycleService.completeTask(task, ReplantTaskStatus.FAILED, reason)
                }
            }
            FailureStrategy.DROP_SAPLING -> {
                if (task.failureReason == DROP_SAPLING_DELIVERED) {
                    lifecycleService.completeTask(task, ReplantTaskStatus.FAILED, reason)
                    return
                }
                val marked = task.transition(
                    next = ReplantTaskStatus.REPLANT_PENDING,
                    now = System.currentTimeMillis(),
                    reason = DROP_SAPLING_DELIVERED,
                    attempts = task.attempts + 1
                )
                lifecycleService.saveTask(marked)
                dropSaplings(marked, fromRegionThread)
                lifecycleService.completeTask(marked, ReplantTaskStatus.FAILED, reason)
            }
            FailureStrategy.RECORD_AND_RECOVER -> {
                val updated = task.transition(
                    next = ReplantTaskStatus.REPLANT_PENDING,
                    now = System.currentTimeMillis(),
                    reason = reason,
                    attempts = task.attempts + 1
                )
                lifecycleService.saveTask(updated)
            }
        }
    }

    private fun dropSaplings(task: ReplantTaskRecord, fromRegionThread: Boolean) {
        val world = resolveWorld(task.rootLocation) ?: return
        val drop: () -> Unit = {
            val location = task.rootLocation.toLocation(world).add(0.5, 0.25, 0.5)
            world.dropItemNaturally(location, ItemStack(task.kind.saplingMaterial, task.saplingLocations.size.coerceAtLeast(1)))
        }
        if (fromRegionThread) {
            drop()
        } else {
            scheduler.runAt(world, task.rootLocation.chunkX, task.rootLocation.chunkZ, drop)
        }
    }

    private fun spawnPlantParticles(world: World, task: ReplantTaskRecord) {
        val particleConfig = configService.current().effects.plantParticles
        if (!particleConfig.enabled || particleConfig.count <= 0) return
        task.saplingLocations.forEach { location ->
            world.spawnParticle(
                particleConfig.particle,
                location.x + 0.5,
                location.y + 0.35,
                location.z + 0.5,
                particleConfig.count,
                particleConfig.offset,
                particleConfig.offset * 0.6,
                particleConfig.offset,
                particleConfig.extra
            )
        }
    }

    private fun resolveWorld(location: BlockLocation): World? =
        Bukkit.getWorld(location.worldId) ?: Bukkit.getWorld(location.worldName)

    companion object {
        private const val ROOT_STILL_PRESENT = "root-still-present"
        private const val DROP_SAPLING_DELIVERED = "drop-sapling-delivered"
    }
}
