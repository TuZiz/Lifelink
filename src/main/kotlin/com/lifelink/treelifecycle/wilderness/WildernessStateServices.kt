package com.lifelink.treelifecycle.wilderness

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class AssetIndexService(
    snapshot: WildernessSnapshot,
    private val repository: WildernessRepository,
    private val logger: Logger
) {
    private val assets = ConcurrentHashMap<UUID, PlayerAssetRecord>(snapshot.assets)

    fun record(block: Block, type: AssetType, player: Player?, material: Material = block.type) {
        val now = System.currentTimeMillis()
        val record = PlayerAssetRecord(
            id = UUID.randomUUID(),
            worldId = block.world.uid,
            worldName = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            chunkX = block.chunk.x,
            chunkZ = block.chunk.z,
            assetType = type,
            material = material.name,
            playerUuid = player?.uniqueId,
            createdAt = now,
            lastSeenAt = now
        )
        assets[record.id] = record
        repository.saveAssetAsync(record).whenComplete { _, error ->
            if (error != null) logger.log(Level.WARNING, "Failed to persist wilderness asset index", error)
        }
    }

    fun recordActivity(location: Location, player: Player?, type: AssetType = AssetType.FREQUENT_ACTIVITY) {
        val block = location.block
        record(block, type, player, block.type)
    }

    fun assetsIn(box: BlockBox): List<PlayerAssetRecord> = assets.values.filter {
        it.worldId == box.worldId && box.contains(it.x, it.y, it.z)
    }

    fun assetsInChunk(worldId: UUID, chunkX: Int, chunkZ: Int): List<PlayerAssetRecord> = assets.values.filter {
        it.worldId == worldId && it.chunkX == chunkX && it.chunkZ == chunkZ
    }
}

class ManualProtectionService(
    snapshot: WildernessSnapshot,
    private val repository: WildernessRepository,
    private val logger: Logger
) {
    private val areas = ConcurrentHashMap<UUID, ProtectedAreaRecord>(snapshot.protectedAreas)

    fun list(): List<ProtectedAreaRecord> = areas.values.sortedBy { it.createdAt }

    fun create(name: String, box: BlockBox, creator: UUID?): ProtectedAreaRecord {
        val record = ProtectedAreaRecord(UUID.randomUUID(), name, box, creator, System.currentTimeMillis())
        areas[record.id] = record
        repository.saveProtectedAreaAsync(record).whenComplete { _, error ->
            if (error != null) logger.log(Level.WARNING, "Failed to persist wilderness protected area", error)
        }
        return record
    }

    fun remove(id: UUID): Boolean {
        val removed = areas.remove(id) ?: return false
        repository.removeProtectedAreaAsync(removed.id).whenComplete { _, error ->
            if (error != null) logger.log(Level.WARNING, "Failed to remove wilderness protected area", error)
        }
        return true
    }

    fun areasIn(box: BlockBox): List<ProtectedAreaRecord> = areas.values.filter { it.box.overlaps(box) }
    fun hit(x: Int, y: Int, z: Int, worldId: UUID): ProtectedAreaRecord? = areas.values.firstOrNull {
        it.box.worldId == worldId && it.box.contains(x, y, z)
    }
}

class AreaLockService {
    private val lockedChunks = ConcurrentHashMap.newKeySet<String>()

    fun tryLock(box: BlockBox): Boolean {
        val keys = box.chunks().map { (x, z) -> key(box.worldId, x, z) }
        synchronized(lockedChunks) {
            if (keys.any { it in lockedChunks }) return false
            lockedChunks.addAll(keys)
        }
        return true
    }

    fun unlock(box: BlockBox) {
        val keys = box.chunks().map { (x, z) -> key(box.worldId, x, z) }
        synchronized(lockedChunks) { keys.forEach(lockedChunks::remove) }
    }

    private fun key(worldId: UUID, chunkX: Int, chunkZ: Int): String = "$worldId:$chunkX:$chunkZ"
}
