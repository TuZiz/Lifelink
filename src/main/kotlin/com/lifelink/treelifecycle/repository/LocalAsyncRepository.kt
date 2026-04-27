package com.lifelink.treelifecycle.repository

import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.LifecycleState
import com.lifelink.treelifecycle.domain.ManagedTreeRecord
import com.lifelink.treelifecycle.domain.ReplantTaskRecord
import com.lifelink.treelifecycle.domain.ReplantTaskStatus
import com.lifelink.treelifecycle.domain.RepositorySnapshot
import com.lifelink.treelifecycle.domain.TreeKind
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

class LocalAsyncRepository(
    private val file: Path,
    private val flushDelayMillis: Long,
    private val logger: Logger
) : TreeRepository {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LifeLink-Repository-IO")
    }
    private val managedTrees = linkedMapOf<UUID, ManagedTreeRecord>()
    private val tasks = linkedMapOf<UUID, ReplantTaskRecord>()
    private var dirty = false
    private var scheduledFlush: ScheduledFuture<*>? = null

    override fun loadAsync(): CompletableFuture<RepositorySnapshot> =
        CompletableFuture.supplyAsync({
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) {
                flushLocked()
                return@supplyAsync RepositorySnapshot.EMPTY
            }

            val properties = Properties()
            Files.newInputStream(file).use { properties.load(it) }

            managedTrees.clear()
            tasks.clear()
            readManagedTrees(properties).forEach { managedTrees[it.id] = it }
            readTasks(properties).forEach { tasks[it.id] = it }
            snapshotLocked()
        }, executor)

    override fun saveManagedTreeAsync(record: ManagedTreeRecord): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            managedTrees[record.id] = record
            scheduleFlushLocked()
        }, executor)

    override fun removeManagedTreeAsync(id: UUID): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            managedTrees.remove(id)
            scheduleFlushLocked()
        }, executor)

    override fun saveTaskAsync(record: ReplantTaskRecord): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            tasks[record.id] = record
            scheduleFlushLocked()
        }, executor)

    override fun removeTaskAsync(id: UUID): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            tasks.remove(id)
            scheduleFlushLocked()
        }, executor)

    override fun flushAsync(): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            scheduledFlush?.cancel(false)
            scheduledFlush = null
            flushIfDirtyLocked()
        }, executor)

    override fun close() {
        executor.execute {
            runCatching {
                scheduledFlush?.cancel(false)
                scheduledFlush = null
                flushIfDirtyLocked()
            }.onFailure {
                logger.log(Level.WARNING, "Failed to flush LifeLink repository on shutdown", it)
            }
            executor.shutdown()
        }
    }

    private fun snapshotLocked(): RepositorySnapshot =
        RepositorySnapshot(managedTrees.toMap(), tasks.toMap())

    private fun scheduleFlushLocked() {
        dirty = true
        if (flushDelayMillis <= 0) {
            flushIfDirtyLocked()
            return
        }
        val current = scheduledFlush
        if (current != null && !current.isDone && !current.isCancelled) return

        // 只合并磁盘写入，不延迟内存状态更新；同一窗口内 100 次状态变更最终只落盘一次。
        scheduledFlush = executor.schedule({
            runCatching {
                scheduledFlush = null
                flushIfDirtyLocked()
            }.onFailure {
                logger.log(Level.SEVERE, "Failed to batch flush LifeLink repository", it)
            }
        }, flushDelayMillis, TimeUnit.MILLISECONDS)
    }

    private fun flushIfDirtyLocked() {
        if (!dirty) return
        try {
            flushLocked()
            dirty = false
        } catch (error: Throwable) {
            dirty = true
            throw error
        }
    }

    private fun flushLocked() {
        Files.createDirectories(file.parent)
        val properties = Properties()
        properties["format"] = "lifelink-state-v1"
        properties["managed.count"] = managedTrees.size.toString()
        managedTrees.values.forEachIndexed { index, record -> writeManagedTree(properties, "managed.$index", record) }
        properties["tasks.count"] = tasks.size.toString()
        tasks.values.forEachIndexed { index, task -> writeTask(properties, "tasks.$index", task) }

        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newOutputStream(temp).use { properties.store(it, "LifeLink async state") }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeManagedTree(properties: Properties, prefix: String, record: ManagedTreeRecord) {
        properties["$prefix.id"] = record.id.toString()
        properties["$prefix.kind"] = record.kind.name
        properties["$prefix.state"] = record.lifecycleState.name
        properties["$prefix.saplings"] = encodeLocations(record.saplingLocations)
        properties["$prefix.logs"] = encodeLocations(record.logLocations)
        properties["$prefix.created"] = record.createdAtEpochMillis.toString()
        properties["$prefix.updated"] = record.updatedAtEpochMillis.toString()
        properties["$prefix.version"] = record.version.toString()
    }

    private fun writeTask(properties: Properties, prefix: String, task: ReplantTaskRecord) {
        properties["$prefix.id"] = task.id.toString()
        properties["$prefix.lifecycle"] = task.lifecycleId.toString()
        properties["$prefix.kind"] = task.kind.name
        properties["$prefix.root"] = encodeLocation(task.rootLocation)
        properties["$prefix.saplings"] = encodeLocations(task.saplingLocations)
        properties["$prefix.status"] = task.status.name
        properties["$prefix.attempts"] = task.attempts.toString()
        properties["$prefix.playerUuid"] = task.playerUuid?.toString() ?: ""
        properties["$prefix.playerName"] = task.playerName ?: ""
        properties["$prefix.failureReason"] = task.failureReason ?: ""
        properties["$prefix.created"] = task.createdAtEpochMillis.toString()
        properties["$prefix.updated"] = task.updatedAtEpochMillis.toString()
        properties["$prefix.version"] = task.version.toString()
    }

    private fun readManagedTrees(properties: Properties): List<ManagedTreeRecord> =
        (0 until properties.getProperty("managed.count", "0").toIntOrNull().orZero()).mapNotNull { index ->
            runCatching {
                val prefix = "managed.$index"
                ManagedTreeRecord(
                    id = UUID.fromString(properties.getProperty("$prefix.id")),
                    kind = TreeKind.valueOf(properties.getProperty("$prefix.kind")),
                    lifecycleState = LifecycleState.valueOf(properties.getProperty("$prefix.state")),
                    saplingLocations = decodeLocations(properties.getProperty("$prefix.saplings", "")),
                    logLocations = decodeLocations(properties.getProperty("$prefix.logs", "")),
                    createdAtEpochMillis = properties.getProperty("$prefix.created", "0").toLong(),
                    updatedAtEpochMillis = properties.getProperty("$prefix.updated", "0").toLong(),
                    version = properties.getProperty("$prefix.version", "0").toLong()
                )
            }.onFailure {
                logger.log(Level.WARNING, "Skipping corrupted managed tree record $index", it)
            }.getOrNull()
        }

    private fun readTasks(properties: Properties): List<ReplantTaskRecord> =
        (0 until properties.getProperty("tasks.count", "0").toIntOrNull().orZero()).mapNotNull { index ->
            runCatching {
                val prefix = "tasks.$index"
                ReplantTaskRecord(
                    id = UUID.fromString(properties.getProperty("$prefix.id")),
                    lifecycleId = UUID.fromString(properties.getProperty("$prefix.lifecycle")),
                    kind = TreeKind.valueOf(properties.getProperty("$prefix.kind")),
                    rootLocation = decodeLocation(properties.getProperty("$prefix.root")),
                    saplingLocations = decodeLocations(properties.getProperty("$prefix.saplings", "")),
                    status = ReplantTaskStatus.valueOf(properties.getProperty("$prefix.status")),
                    attempts = properties.getProperty("$prefix.attempts", "0").toInt(),
                    playerUuid = properties.getProperty("$prefix.playerUuid").takeUnless { it.isNullOrBlank() }?.let(UUID::fromString),
                    playerName = properties.getProperty("$prefix.playerName").takeUnless { it.isNullOrBlank() },
                    failureReason = properties.getProperty("$prefix.failureReason").takeUnless { it.isNullOrBlank() },
                    createdAtEpochMillis = properties.getProperty("$prefix.created", "0").toLong(),
                    updatedAtEpochMillis = properties.getProperty("$prefix.updated", "0").toLong(),
                    version = properties.getProperty("$prefix.version", "0").toLong()
                )
            }.onFailure {
                logger.log(Level.WARNING, "Skipping corrupted replant task record $index", it)
            }.getOrNull()
        }

    private fun encodeLocations(locations: Set<BlockLocation>): String =
        locations.joinToString(";") { encodeLocation(it) }

    private fun decodeLocations(raw: String): Set<BlockLocation> =
        if (raw.isBlank()) emptySet() else raw.split(';').map(::decodeLocation).toSet()

    private fun encodeLocation(location: BlockLocation): String =
        listOf(
            location.worldId.toString(),
            Base64.getUrlEncoder().withoutPadding().encodeToString(location.worldName.toByteArray(Charsets.UTF_8)),
            location.x.toString(),
            location.y.toString(),
            location.z.toString()
        ).joinToString(",")

    private fun decodeLocation(raw: String): BlockLocation {
        val parts = raw.split(',')
        require(parts.size == 5) { "Invalid location encoding: $raw" }
        return BlockLocation(
            worldId = UUID.fromString(parts[0]),
            worldName = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8),
            x = parts[2].toInt(),
            y = parts[3].toInt(),
            z = parts[4].toInt()
        )
    }

    private fun Int?.orZero(): Int = this ?: 0
}
