package com.lifelink.treelifecycle.wilderness

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WildernessRepository(
    private val root: Path,
    private val logger: Logger
) : AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LifeLink-Wilderness-IO").apply { isDaemon = true }
    }
    private val stateFile = root.resolve("state.properties")
    private val backupDir = root.resolve("backups")
    private val auditFile = root.resolve("audit.log")
    private val assets = linkedMapOf<UUID, PlayerAssetRecord>()
    private val protectedAreas = linkedMapOf<UUID, ProtectedAreaRecord>()
    private val jobs = linkedMapOf<UUID, RestoreJobRecord>()

    fun loadAsync(): CompletableFuture<WildernessSnapshot> = CompletableFuture.supplyAsync({
        Files.createDirectories(root)
        Files.createDirectories(backupDir)
        if (!Files.exists(stateFile)) {
            flushLocked()
            return@supplyAsync WildernessSnapshot.EMPTY
        }
        val properties = Properties()
        Files.newInputStream(stateFile).use(properties::load)
        assets.clear()
        protectedAreas.clear()
        jobs.clear()
        readAssets(properties).forEach { assets[it.id] = it }
        readProtectedAreas(properties).forEach { protectedAreas[it.id] = it }
        readJobs(properties).forEach { jobs[it.jobId] = it }
        snapshotLocked()
    }, executor)

    fun snapshot(): WildernessSnapshot = synchronized(this) { snapshotLocked() }

    fun saveAssetAsync(record: PlayerAssetRecord): CompletableFuture<Void> = CompletableFuture.runAsync({
        val existing = assets.values.firstOrNull {
            it.worldId == record.worldId && it.x == record.x && it.y == record.y && it.z == record.z && it.assetType == record.assetType
        }
        if (existing != null) {
            assets[existing.id] = existing.copy(lastSeenAt = record.lastSeenAt, material = record.material, playerUuid = record.playerUuid ?: existing.playerUuid)
        } else {
            assets[record.id] = record
        }
        flushLocked()
    }, executor)

    fun saveProtectedAreaAsync(record: ProtectedAreaRecord): CompletableFuture<Void> = CompletableFuture.runAsync({
        protectedAreas[record.id] = record
        flushLocked()
    }, executor)

    fun removeProtectedAreaAsync(id: UUID): CompletableFuture<Void> = CompletableFuture.runAsync({
        protectedAreas.remove(id)
        flushLocked()
    }, executor)

    fun saveJobAsync(record: RestoreJobRecord): CompletableFuture<Void> = CompletableFuture.runAsync({
        jobs[record.jobId] = record
        flushLocked()
    }, executor)

    fun appendAuditAsync(line: String): CompletableFuture<Void> = CompletableFuture.runAsync({
        Files.createDirectories(root)
        Files.writeString(
            auditFile,
            line + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }, executor)

    fun writeBackupAsync(jobId: UUID, blocks: List<BackupBlock>, gzip: Boolean): CompletableFuture<Pair<Path, String>> =
        CompletableFuture.supplyAsync({
            Files.createDirectories(backupDir)
            val suffix = if (gzip) ".snapshot.gz" else ".snapshot"
            val target = backupDir.resolve("$jobId$suffix")
            val temp = backupDir.resolve("$jobId$suffix.tmp")
            val writerFactory: () -> BufferedWriter = {
                val output = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                val stream = if (gzip) GZIPOutputStream(output) else output
                stream.bufferedWriter(StandardCharsets.UTF_8)
            }
            writerFactory().use { writer ->
                writer.write("version=1")
                writer.newLine()
                writer.write("jobId=$jobId")
                writer.newLine()
                blocks.forEach { block ->
                    writer.write("${block.x},${block.y},${block.z},${encode(block.blockData)}")
                    writer.newLine()
                }
            }
            forceFile(temp)
            moveAtomic(temp, target)
            target to sha256(target)
        }, executor)

    fun readBackupAsync(path: Path): CompletableFuture<List<BackupBlock>> = CompletableFuture.supplyAsync({
        if (!Files.exists(path)) return@supplyAsync emptyList()
        val gzip = path.fileName.toString().endsWith(".gz")
        val reader: BufferedReader = run {
            val input = Files.newInputStream(path)
            val stream = if (gzip) GZIPInputStream(input) else input
            stream.bufferedReader(StandardCharsets.UTF_8)
        }
        reader.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("version=") && !it.startsWith("jobId=") }
                .mapNotNull { line ->
                    val parts = line.split(',', limit = 4)
                    if (parts.size != 4) return@mapNotNull null
                    BackupBlock(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), decode(parts[3]))
                }
                .toList()
        }
    }, executor)

    override fun close() {
        executor.execute {
            runCatching { flushLocked() }.onFailure { logger.log(Level.WARNING, "Failed to flush wilderness state", it) }
            executor.shutdown()
        }
    }

    private fun snapshotLocked(): WildernessSnapshot = WildernessSnapshot(assets.toMap(), protectedAreas.toMap(), jobs.toMap())

    private fun flushLocked() {
        Files.createDirectories(root)
        val properties = Properties()
        properties["format"] = "lifelink-wilderness-v1"
        properties["assets.count"] = assets.size.toString()
        assets.values.forEachIndexed { index, record -> writeAsset(properties, "assets.$index", record) }
        properties["protected.count"] = protectedAreas.size.toString()
        protectedAreas.values.forEachIndexed { index, area -> writeProtectedArea(properties, "protected.$index", area) }
        properties["jobs.count"] = jobs.size.toString()
        jobs.values.forEachIndexed { index, job -> writeJob(properties, "jobs.$index", job) }
        val temp = stateFile.resolveSibling(stateFile.fileName.toString() + ".tmp")
        Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            properties.store(output, "LifeLink conservative wilderness restore state")
        }
        forceFile(temp)
        moveAtomic(temp, stateFile)
    }

    private fun writeAsset(properties: Properties, prefix: String, record: PlayerAssetRecord) {
        properties["$prefix.id"] = record.id.toString()
        properties["$prefix.worldId"] = record.worldId.toString()
        properties["$prefix.worldName"] = encode(record.worldName)
        properties["$prefix.x"] = record.x.toString()
        properties["$prefix.y"] = record.y.toString()
        properties["$prefix.z"] = record.z.toString()
        properties["$prefix.chunkX"] = record.chunkX.toString()
        properties["$prefix.chunkZ"] = record.chunkZ.toString()
        properties["$prefix.assetType"] = record.assetType.name
        properties["$prefix.material"] = record.material
        properties["$prefix.playerUuid"] = record.playerUuid?.toString() ?: ""
        properties["$prefix.createdAt"] = record.createdAt.toString()
        properties["$prefix.lastSeenAt"] = record.lastSeenAt.toString()
    }

    private fun writeProtectedArea(properties: Properties, prefix: String, area: ProtectedAreaRecord) {
        properties["$prefix.id"] = area.id.toString()
        properties["$prefix.name"] = encode(area.name)
        writeBox(properties, "$prefix.box", area.box)
        properties["$prefix.createdBy"] = area.createdBy?.toString() ?: ""
        properties["$prefix.createdAt"] = area.createdAt.toString()
    }

    private fun writeJob(properties: Properties, prefix: String, job: RestoreJobRecord) {
        properties["$prefix.jobId"] = job.jobId.toString()
        properties["$prefix.worldId"] = job.worldId.toString()
        properties["$prefix.worldName"] = encode(job.worldName)
        properties["$prefix.originWorldName"] = encode(job.originWorldName)
        properties["$prefix.mode"] = job.mode.name
        writeBox(properties, "$prefix.region", job.region)
        properties["$prefix.creatorUuid"] = job.creatorUuid?.toString() ?: ""
        properties["$prefix.status"] = job.status.name
        properties["$prefix.createdAt"] = job.createdAt.toString()
        properties["$prefix.updatedAt"] = job.updatedAt.toString()
        properties["$prefix.backupPath"] = job.backupPath ?: ""
        properties["$prefix.planHash"] = job.planHash ?: ""
        properties["$prefix.backupHash"] = job.backupHash ?: ""
        properties["$prefix.restoreProgress"] = job.restoreProgress.toString()
        properties["$prefix.rollbackProgress"] = job.rollbackProgress.toString()
        properties["$prefix.affectedChunks"] = job.affectedChunks.toString()
        properties["$prefix.affectedBlocks"] = job.affectedBlocks.toString()
        properties["$prefix.protectedBlocks"] = job.protectedBlocks.toString()
        properties["$prefix.skippedBlocks"] = job.skippedBlocks.toString()
        properties["$prefix.errorMessage"] = encode(job.errorMessage ?: "")
        properties["$prefix.confirmed"] = job.confirmed.toString()
    }

    private fun writeBox(properties: Properties, prefix: String, box: BlockBox) {
        properties["$prefix.worldId"] = box.worldId.toString()
        properties["$prefix.worldName"] = encode(box.worldName)
        properties["$prefix.minX"] = box.minX.toString()
        properties["$prefix.minY"] = box.minY.toString()
        properties["$prefix.minZ"] = box.minZ.toString()
        properties["$prefix.maxX"] = box.maxX.toString()
        properties["$prefix.maxY"] = box.maxY.toString()
        properties["$prefix.maxZ"] = box.maxZ.toString()
    }

    private fun readAssets(properties: Properties): List<PlayerAssetRecord> =
        (0 until properties.getProperty("assets.count", "0").toInt()).mapNotNull { index ->
            runCatching {
                val prefix = "assets.$index"
                PlayerAssetRecord(
                    id = UUID.fromString(properties.getProperty("$prefix.id")),
                    worldId = UUID.fromString(properties.getProperty("$prefix.worldId")),
                    worldName = decode(properties.getProperty("$prefix.worldName")),
                    x = properties.getProperty("$prefix.x").toInt(),
                    y = properties.getProperty("$prefix.y").toInt(),
                    z = properties.getProperty("$prefix.z").toInt(),
                    chunkX = properties.getProperty("$prefix.chunkX").toInt(),
                    chunkZ = properties.getProperty("$prefix.chunkZ").toInt(),
                    assetType = AssetType.valueOf(properties.getProperty("$prefix.assetType")),
                    material = properties.getProperty("$prefix.material", "UNKNOWN"),
                    playerUuid = properties.getProperty("$prefix.playerUuid").takeUnless { it.isNullOrBlank() }?.let(UUID::fromString),
                    createdAt = properties.getProperty("$prefix.createdAt", "0").toLong(),
                    lastSeenAt = properties.getProperty("$prefix.lastSeenAt", "0").toLong()
                )
            }.onFailure { logger.log(Level.WARNING, "Skipping corrupted wilderness asset $index", it) }.getOrNull()
        }

    private fun readProtectedAreas(properties: Properties): List<ProtectedAreaRecord> =
        (0 until properties.getProperty("protected.count", "0").toInt()).mapNotNull { index ->
            runCatching {
                val prefix = "protected.$index"
                ProtectedAreaRecord(
                    id = UUID.fromString(properties.getProperty("$prefix.id")),
                    name = decode(properties.getProperty("$prefix.name")),
                    box = readBox(properties, "$prefix.box"),
                    createdBy = properties.getProperty("$prefix.createdBy").takeUnless { it.isNullOrBlank() }?.let(UUID::fromString),
                    createdAt = properties.getProperty("$prefix.createdAt", "0").toLong()
                )
            }.onFailure { logger.log(Level.WARNING, "Skipping corrupted protected area $index", it) }.getOrNull()
        }

    private fun readJobs(properties: Properties): List<RestoreJobRecord> =
        (0 until properties.getProperty("jobs.count", "0").toInt()).mapNotNull { index ->
            runCatching {
                val prefix = "jobs.$index"
                RestoreJobRecord(
                    jobId = UUID.fromString(properties.getProperty("$prefix.jobId")),
                    worldId = UUID.fromString(properties.getProperty("$prefix.worldId")),
                    worldName = decode(properties.getProperty("$prefix.worldName")),
                    originWorldName = decode(properties.getProperty("$prefix.originWorldName")),
                    mode = RestoreMode.valueOf(properties.getProperty("$prefix.mode")),
                    region = readBox(properties, "$prefix.region"),
                    creatorUuid = properties.getProperty("$prefix.creatorUuid").takeUnless { it.isNullOrBlank() }?.let(UUID::fromString),
                    status = RestoreJobStatus.valueOf(properties.getProperty("$prefix.status")),
                    createdAt = properties.getProperty("$prefix.createdAt", "0").toLong(),
                    updatedAt = properties.getProperty("$prefix.updatedAt", "0").toLong(),
                    backupPath = properties.getProperty("$prefix.backupPath").takeUnless { it.isNullOrBlank() },
                    planHash = properties.getProperty("$prefix.planHash").takeUnless { it.isNullOrBlank() },
                    backupHash = properties.getProperty("$prefix.backupHash").takeUnless { it.isNullOrBlank() },
                    restoreProgress = properties.getProperty("$prefix.restoreProgress", "0").toInt(),
                    rollbackProgress = properties.getProperty("$prefix.rollbackProgress", "0").toInt(),
                    affectedChunks = properties.getProperty("$prefix.affectedChunks", "0").toInt(),
                    affectedBlocks = properties.getProperty("$prefix.affectedBlocks", "0").toInt(),
                    protectedBlocks = properties.getProperty("$prefix.protectedBlocks", "0").toInt(),
                    skippedBlocks = properties.getProperty("$prefix.skippedBlocks", "0").toInt(),
                    errorMessage = decode(properties.getProperty("$prefix.errorMessage", "")).takeUnless { it.isBlank() },
                    confirmed = properties.getProperty("$prefix.confirmed", "false").toBoolean()
                )
            }.onFailure { logger.log(Level.WARNING, "Skipping corrupted restore job $index", it) }.getOrNull()
        }

    private fun readBox(properties: Properties, prefix: String): BlockBox = BlockBox(
        worldId = UUID.fromString(properties.getProperty("$prefix.worldId")),
        worldName = decode(properties.getProperty("$prefix.worldName")),
        minX = properties.getProperty("$prefix.minX").toInt(),
        minY = properties.getProperty("$prefix.minY").toInt(),
        minZ = properties.getProperty("$prefix.minZ").toInt(),
        maxX = properties.getProperty("$prefix.maxX").toInt(),
        maxY = properties.getProperty("$prefix.maxY").toInt(),
        maxZ = properties.getProperty("$prefix.maxZ").toInt()
    )

    private fun forceFile(path: Path) {
        runCatching {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun moveAtomic(temp: Path, target: Path) {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String?): String = if (value.isNullOrBlank()) "" else String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
