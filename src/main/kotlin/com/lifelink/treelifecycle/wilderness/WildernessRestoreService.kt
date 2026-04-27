package com.lifelink.treelifecycle.wilderness

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import org.bukkit.Bukkit
import org.bukkit.World
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

class WildernessRestoreService(
    snapshot: WildernessSnapshot,
    private val configService: ConfigService,
    private val scheduler: SchedulerAdapter,
    private val repository: WildernessRepository,
    private val assetIndexService: AssetIndexService,
    private val manualProtectionService: ManualProtectionService,
    private val logger: Logger
) {
    private val originWorldService = OriginWorldService { configService.current().wilderness }
    private val scanner = BuildingScanner({ configService.current().wilderness }, assetIndexService, manualProtectionService, originWorldService)
    private val previewService = PreviewService { configService.current().wilderness }
    private val areaLocks = AreaLockService()
    private val jobs = ConcurrentHashMap<UUID, RestoreJobRecord>(snapshot.jobs)

    fun recoverUnfinishedJobs() {
        jobs.values.filter { !it.status.isTerminal() }.forEach { job ->
            val recovered = job.transition(
                RestoreJobStatus.FAILED_RECOVERABLE,
                error = "server-restarted-before-job-finished"
            )
            jobs[recovered.jobId] = recovered
            repository.saveJobAsync(recovered)
        }
    }

    fun scanArea(world: World, box: BlockBox, surfaceOnly: Boolean, callback: (AreaScanReport) -> Unit) {
        val chunks = box.chunks().take(configService.current().wilderness.scanner.maxChunksPerScan)
        val reports = mutableListOf<ChunkRiskReport>()
        fun next(index: Int) {
            if (index >= chunks.size) {
                val mask = ProtectionMaskService({ configService.current().wilderness }, assetIndexService, manualProtectionService)
                    .buildMask(box, reports)
                callback(AreaScanReport(world.uid, world.name, box, reports.toList(), mask))
                return
            }
            val (chunkX, chunkZ) = chunks[index]
            scheduler.runAt(world, chunkX, chunkZ) {
                reports += scanner.scanChunk(world, chunkX, chunkZ, box, surfaceOnly)
                next(index + 1)
            }
        }
        next(0)
    }

    fun previewArea(world: World, box: BlockBox, surfaceOnly: Boolean, callback: (AreaScanReport) -> Unit) {
        scanArea(world, box, surfaceOnly) { report ->
            previewService.show(world, report)
            callback(report)
        }
    }

    fun createRestore(
        creatorUuid: UUID?,
        world: World,
        box: BlockBox,
        mode: RestoreMode,
        surfaceOnly: Boolean,
        callback: (RestoreJobRecord, String) -> Unit
    ) {
        if (!configService.current().wilderness.enabled) {
            callback(failedJob(creatorUuid, world, box, mode, "wilderness-disabled"), "wilderness-restore-failed")
            return
        }
        scanArea(world, box, surfaceOnly) { report ->
            val now = System.currentTimeMillis()
            val initial = RestoreJobRecord(
                jobId = UUID.randomUUID(),
                worldId = world.uid,
                worldName = world.name,
                originWorldName = originWorldService.originWorldName(),
                mode = mode,
                region = box,
                creatorUuid = creatorUuid,
                status = RestoreJobStatus.PLANNED,
                createdAt = now,
                updatedAt = now,
                backupPath = null,
                planHash = null,
                backupHash = null,
                restoreProgress = 0,
                rollbackProgress = 0,
                affectedChunks = box.chunks().size,
                affectedBlocks = report.recoverableBlocks,
                protectedBlocks = report.protectedBlocks,
                skippedBlocks = report.skippedBlocks,
                errorMessage = null,
                confirmed = false
            )
            jobs[initial.jobId] = initial
            repository.saveJobAsync(initial)

            when (report.highestRisk) {
                RiskLevel.HIGH -> {
                    val failed = initial.transition(RestoreJobStatus.FAILED, error = "high-risk-area-protected")
                    jobs[failed.jobId] = failed
                    repository.saveJobAsync(failed)
                    callback(failed, "wilderness-restore-failed")
                }
                RiskLevel.MEDIUM -> {
                    callback(initial, "wilderness-confirm-required")
                }
                RiskLevel.LOW -> executeRestore(initial.copy(confirmed = true), report, surfaceOnly, callback)
            }
        }
    }

    fun confirm(jobId: UUID, callback: (RestoreJobRecord, String) -> Unit) {
        val job = jobs[jobId]
        if (job == null) {
            callback(missingJob(jobId), "wilderness-restore-failed")
            return
        }
        if (job.status != RestoreJobStatus.PLANNED) {
            callback(job, "wilderness-job-not-confirmable")
            return
        }
        val world = Bukkit.getWorld(job.worldId) ?: Bukkit.getWorld(job.worldName)
        if (world == null) {
            val failed = job.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = "world-unavailable")
            jobs[jobId] = failed
            repository.saveJobAsync(failed)
            callback(failed, "wilderness-restore-failed")
            return
        }
        val confirmed = job.transition(RestoreJobStatus.SCANNING, confirmed = true)
        jobs[jobId] = confirmed
        repository.saveJobAsync(confirmed)
        val surfaceOnly = confirmed.mode == RestoreMode.SURFACE
        scanArea(world, confirmed.region, surfaceOnly) { report ->
            if (report.highestRisk == RiskLevel.HIGH) {
                val failed = confirmed.transition(RestoreJobStatus.FAILED, error = "high-risk-area-protected-after-rescan")
                jobs[jobId] = failed
                repository.saveJobAsync(failed)
                callback(failed, "wilderness-restore-failed")
            } else {
                executeRestore(confirmed, report, surfaceOnly, callback)
            }
        }
    }

    fun cancel(jobId: UUID): RestoreJobRecord? {
        val job = jobs[jobId] ?: return null
        if (job.status.isTerminal()) return job
        val cancelled = job.transition(RestoreJobStatus.CANCELLED, error = "cancelled-by-admin")
        jobs[jobId] = cancelled
        repository.saveJobAsync(cancelled)
        areaLocks.unlock(cancelled.region)
        return cancelled
    }

    fun rollback(jobId: UUID, callback: (RestoreJobRecord, String) -> Unit) {
        val job = jobs[jobId]
        if (job == null) {
            callback(missingJob(jobId), "wilderness-restore-failed")
            return
        }
        val backupPath = job.backupPath
        if (backupPath.isNullOrBlank()) {
            callback(job.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = "backup-missing"), "wilderness-restore-failed")
            return
        }
        val world = Bukkit.getWorld(job.worldId) ?: Bukkit.getWorld(job.worldName)
        if (world == null) {
            callback(job.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = "world-unavailable"), "wilderness-restore-failed")
            return
        }
        val rolling = job.transition(RestoreJobStatus.ROLLING_BACK, rollbackProgress = 0)
        jobs[jobId] = rolling
        repository.saveJobAsync(rolling)
        repository.readBackupAsync(Path.of(backupPath)).whenComplete { blocks, error ->
            if (error != null) {
                val failed = rolling.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = error.message ?: "backup-read-failed")
                jobs[jobId] = failed
                repository.saveJobAsync(failed)
                callback(failed, "wilderness-restore-failed")
                return@whenComplete
            }
            applyRollback(world, rolling, blocks, callback)
        }
    }

    fun jobs(): List<RestoreJobRecord> = jobs.values.sortedByDescending { it.createdAt }
    fun job(id: UUID): RestoreJobRecord? = jobs[id]

    private fun executeRestore(
        job: RestoreJobRecord,
        report: AreaScanReport,
        surfaceOnly: Boolean,
        callback: (RestoreJobRecord, String) -> Unit
    ) {
        val origin = originWorldService.originWorld()
        if (origin == null) {
            val failed = job.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = "origin-world-missing:${originWorldService.originWorldName()}")
            jobs[failed.jobId] = failed
            repository.saveJobAsync(failed)
            callback(failed, "wilderness-restore-failed")
            return
        }
        if (!areaLocks.tryLock(job.region)) {
            val failed = job.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = "area-locked")
            jobs[failed.jobId] = failed
            repository.saveJobAsync(failed)
            callback(failed, "wilderness-restore-failed")
            return
        }
        val planning = job.transition(RestoreJobStatus.BACKUP_PENDING, protectedBlocks = report.protectedBlocks, skippedBlocks = report.skippedBlocks)
        jobs[planning.jobId] = planning
        repository.saveJobAsync(planning)
        collectPlan(origin, planning.region, report.mask, surfaceOnly) { plan ->
            val planHash = sha256(plan.joinToString("\n") { "${it.x},${it.y},${it.z},${it.targetBlockData}" })
            collectBackupAndSafePlan(Bukkit.getWorld(planning.worldId) ?: return@collectPlan, planning.region, report.mask, plan) { backup, safePlan, skipped ->
                val backing = planning.transition(
                    RestoreJobStatus.BACKING_UP,
                    planHash = planHash,
                    affectedBlocks = safePlan.size,
                    skippedBlocks = planning.skippedBlocks + skipped
                )
                jobs[backing.jobId] = backing
                repository.saveJobAsync(backing)
                repository.writeBackupAsync(backing.jobId, backup, configService.current().wilderness.backup.compression == "gzip")
                    .whenComplete { result, error ->
                        if (error != null) {
                            val failed = backing.transition(RestoreJobStatus.FAILED_RECOVERABLE, error = error.message ?: "backup-write-failed")
                            jobs[failed.jobId] = failed
                            repository.saveJobAsync(failed)
                            areaLocks.unlock(failed.region)
                            callback(failed, "wilderness-restore-failed")
                            return@whenComplete
                        }
                        val (path, backupHash) = result
                        val ready = backing.transition(
                            RestoreJobStatus.RESTORE_PENDING,
                            backupPath = path.toAbsolutePath().toString(),
                            backupHash = backupHash
                        )
                        jobs[ready.jobId] = ready
                        repository.saveJobAsync(ready)
                        applyRestore(Bukkit.getWorld(ready.worldId) ?: return@whenComplete, ready, report.mask, safePlan, callback)
                    }
            }
        }
    }

    private fun collectPlan(
        origin: World,
        box: BlockBox,
        mask: ProtectionMask,
        surfaceOnly: Boolean,
        callback: (List<RestoreBlockPlan>) -> Unit
    ) {
        val chunks = box.chunks().toList()
        val plan = mutableListOf<RestoreBlockPlan>()
        val config = configService.current().wilderness.scanner
        fun next(index: Int) {
            if (index >= chunks.size) {
                callback(plan)
                return
            }
            val (chunkX, chunkZ) = chunks[index]
            scheduler.runAt(origin, chunkX, chunkZ) {
                val minX = max(box.minX, chunkX shl 4)
                val maxX = min(box.maxX, (chunkX shl 4) + 15)
                val minZ = max(box.minZ, chunkZ shl 4)
                val maxZ = min(box.maxZ, (chunkZ shl 4) + 15)
                for (x in minX..maxX) {
                    for (z in minZ..maxZ) {
                        val surfaceY = origin.getHighestBlockYAt(x, z)
                        val minY = if (surfaceOnly) max(origin.minHeight, surfaceY + config.surfaceRestoreMinOffset) else max(origin.minHeight, box.minY)
                        val maxY = if (surfaceOnly) min(origin.maxHeight - 1, surfaceY + config.surfaceRestoreMaxOffset) else min(origin.maxHeight - 1, box.maxY)
                        for (y in minY..maxY) {
                            if (mask.hit(x, y, z) != null) continue
                            val data = origin.getBlockAt(x, y, z).blockData.asString
                            plan += RestoreBlockPlan(x, y, z, data)
                        }
                    }
                }
                next(index + 1)
            }
        }
        next(0)
    }

    private fun collectBackupAndSafePlan(
        world: World,
        box: BlockBox,
        mask: ProtectionMask,
        plan: List<RestoreBlockPlan>,
        callback: (List<BackupBlock>, List<RestoreBlockPlan>, Int) -> Unit
    ) {
        val byChunk = plan.groupBy { Math.floorDiv(it.x, 16) to Math.floorDiv(it.z, 16) }
        val chunks = byChunk.keys.toList()
        val backup = mutableListOf<BackupBlock>()
        val safePlan = mutableListOf<RestoreBlockPlan>()
        var skipped = 0
        fun next(index: Int) {
            if (index >= chunks.size) {
                callback(backup, safePlan, skipped)
                return
            }
            val (chunkX, chunkZ) = chunks[index]
            scheduler.runAt(world, chunkX, chunkZ) {
                byChunk[chunks[index]].orEmpty().forEach { planned ->
                    if (!box.contains(planned.x, planned.y, planned.z) || mask.hit(planned.x, planned.y, planned.z) != null) {
                        skipped++
                        return@forEach
                    }
                    val block = world.getBlockAt(planned.x, planned.y, planned.z)
                    if (WildernessMaterials.isHardDenyBlock(block)) {
                        skipped++
                        return@forEach
                    }
                    val current = block.blockData.asString
                    if (current != planned.targetBlockData) {
                        backup += BackupBlock(planned.x, planned.y, planned.z, current)
                        safePlan += planned
                    }
                }
                next(index + 1)
            }
        }
        next(0)
    }

    private fun applyRestore(
        world: World,
        job: RestoreJobRecord,
        mask: ProtectionMask,
        plan: List<RestoreBlockPlan>,
        callback: (RestoreJobRecord, String) -> Unit
    ) {
        val restoring = job.transition(RestoreJobStatus.RESTORING)
        jobs[job.jobId] = restoring
        repository.saveJobAsync(restoring)
        val byChunk = plan.groupBy { Math.floorDiv(it.x, 16) to Math.floorDiv(it.z, 16) }
        val chunks = byChunk.keys.toList()
        var applied = 0
        var skipped = restoring.skippedBlocks
        fun next(index: Int) {
            if (index >= chunks.size) {
                val done = restoring.transition(
                    RestoreJobStatus.COMPLETED,
                    restoreProgress = applied,
                    affectedBlocks = applied,
                    skippedBlocks = skipped
                )
                jobs[done.jobId] = done
                repository.saveJobAsync(done)
                repository.appendAuditAsync("${System.currentTimeMillis()} restore ${done.jobId} completed blocks=$applied skipped=$skipped")
                areaLocks.unlock(done.region)
                callback(done, "wilderness-restore-completed")
                return
            }
            val (chunkX, chunkZ) = chunks[index]
            scheduler.runAt(world, chunkX, chunkZ) {
                if (shouldPauseForNearbyPlayer(world, chunkX, chunkZ)) {
                    skipped += byChunk[chunks[index]].orEmpty().size
                    next(index + 1)
                    return@runAt
                }
                byChunk[chunks[index]].orEmpty().forEach { planned ->
                    if (mask.hit(planned.x, planned.y, planned.z) != null) {
                        skipped++
                        return@forEach
                    }
                    val block = world.getBlockAt(planned.x, planned.y, planned.z)
                    if (WildernessMaterials.isHardDenyBlock(block)) {
                        skipped++
                        return@forEach
                    }
                    runCatching {
                        block.blockData = Bukkit.createBlockData(planned.targetBlockData)
                        applied++
                    }.onFailure {
                        skipped++
                        logger.log(Level.WARNING, "Skipped invalid wilderness block data in job ${job.jobId}", it)
                    }
                }
                val progress = restoring.transition(RestoreJobStatus.RESTORING, restoreProgress = applied, skippedBlocks = skipped)
                jobs[progress.jobId] = progress
                repository.saveJobAsync(progress)
                next(index + 1)
            }
        }
        next(0)
    }

    private fun applyRollback(
        world: World,
        job: RestoreJobRecord,
        blocks: List<BackupBlock>,
        callback: (RestoreJobRecord, String) -> Unit
    ) {
        val byChunk = blocks.groupBy { Math.floorDiv(it.x, 16) to Math.floorDiv(it.z, 16) }
        val chunks = byChunk.keys.toList()
        var restored = 0
        fun next(index: Int) {
            if (index >= chunks.size) {
                val done = job.transition(RestoreJobStatus.ROLLED_BACK, rollbackProgress = restored)
                jobs[done.jobId] = done
                repository.saveJobAsync(done)
                repository.appendAuditAsync("${System.currentTimeMillis()} rollback ${done.jobId} completed blocks=$restored")
                callback(done, "wilderness-rollback-completed")
                return
            }
            val (chunkX, chunkZ) = chunks[index]
            scheduler.runAt(world, chunkX, chunkZ) {
                byChunk[chunks[index]].orEmpty().forEach { backup ->
                    val block = world.getBlockAt(backup.x, backup.y, backup.z)
                    if (!WildernessMaterials.isHardDenyBlock(block)) {
                        runCatching {
                            block.blockData = Bukkit.createBlockData(backup.blockData)
                            restored++
                        }
                    }
                }
                val progress = job.transition(RestoreJobStatus.ROLLING_BACK, rollbackProgress = restored)
                jobs[progress.jobId] = progress
                repository.saveJobAsync(progress)
                next(index + 1)
            }
        }
        next(0)
    }

    private fun shouldPauseForNearbyPlayer(world: World, chunkX: Int, chunkZ: Int): Boolean {
        val config = configService.current().wilderness.performance
        if (!config.pauseWhenPlayerNearby || config.playerSafeDistance <= 0) return false
        val centerX = (chunkX shl 4) + 8
        val centerZ = (chunkZ shl 4) + 8
        val maxDistanceSquared = config.playerSafeDistance * config.playerSafeDistance
        return world.players.any { player ->
            val dx = player.location.blockX - centerX
            val dz = player.location.blockZ - centerZ
            dx * dx + dz * dz <= maxDistanceSquared
        }
    }

    private fun failedJob(creatorUuid: UUID?, world: World, box: BlockBox, mode: RestoreMode, reason: String): RestoreJobRecord {
        val now = System.currentTimeMillis()
        val job = RestoreJobRecord(
            UUID.randomUUID(),
            world.uid,
            world.name,
            originWorldService.originWorldName(),
            mode,
            box,
            creatorUuid,
            RestoreJobStatus.FAILED,
            now,
            now,
            null,
            null,
            null,
            0,
            0,
            box.chunks().size,
            0,
            0,
            0,
            reason,
            false
        )
        jobs[job.jobId] = job
        repository.saveJobAsync(job)
        return job
    }

    private fun missingJob(jobId: UUID): RestoreJobRecord {
        val now = System.currentTimeMillis()
        val box = BlockBox(UUID(0, 0), "unknown", 0, 0, 0, 0, 0, 0)
        return RestoreJobRecord(jobId, box.worldId, box.worldName, originWorldService.originWorldName(), RestoreMode.CHUNK, box, null,
            RestoreJobStatus.FAILED, now, now, null, null, null, 0, 0, 0, 0, 0, 0, "job-not-found", false)
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
