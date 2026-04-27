package com.lifelink.treelifecycle.wilderness

import java.util.UUID

 enum class AssetType {
    CONTAINER,
    BED,
    SIGN,
    REDSTONE,
    BUILDING_BLOCK,
    FARM,
    HANGING_ENTITY,
    MANUAL_PROTECT,
    FREQUENT_ACTIVITY,
    BUCKET_USE,
    UNKNOWN
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class RestoreMode { CHUNK, RADIUS, SURFACE, SELECTION }

enum class RestoreJobStatus {
    CREATED,
    SCANNING,
    PLANNED,
    BACKUP_PENDING,
    BACKING_UP,
    BACKUP_DONE,
    RESTORE_PENDING,
    RESTORING,
    VERIFYING,
    COMPLETED,
    FAILED,
    FAILED_RECOVERABLE,
    ROLLBACK_PENDING,
    ROLLING_BACK,
    ROLLED_BACK,
    CANCELLED;

    fun isTerminal(): Boolean = this in setOf(COMPLETED, FAILED, ROLLED_BACK, CANCELLED)
}

data class BlockBox(
    val worldId: UUID,
    val worldName: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun overlaps(other: BlockBox): Boolean =
        worldId == other.worldId &&
            minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    fun expand(xz: Int, y: Int): BlockBox = copy(
        minX = minX - xz,
        minY = minY - y,
        minZ = minZ - xz,
        maxX = maxX + xz,
        maxY = maxY + y,
        maxZ = maxZ + xz
    )

    fun chunks(): Set<Pair<Int, Int>> {
        val chunks = linkedSetOf<Pair<Int, Int>>()
        for (cx in minX.floorChunk()..maxX.floorChunk()) {
            for (cz in minZ.floorChunk()..maxZ.floorChunk()) chunks += cx to cz
        }
        return chunks
    }

    private fun Int.floorChunk(): Int = Math.floorDiv(this, 16)
}

data class PlayerAssetRecord(
    val id: UUID,
    val worldId: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val chunkX: Int,
    val chunkZ: Int,
    val assetType: AssetType,
    val material: String,
    val playerUuid: UUID?,
    val createdAt: Long,
    val lastSeenAt: Long
)

data class ProtectedAreaRecord(
    val id: UUID,
    val name: String,
    val box: BlockBox,
    val createdBy: UUID?,
    val createdAt: Long
)

data class ProtectionRegion(
    val box: BlockBox,
    val reason: String
)

data class ProtectionMask(
    val regions: List<ProtectionRegion>
) {
    fun hit(x: Int, y: Int, z: Int): ProtectionRegion? = regions.firstOrNull { it.box.contains(x, y, z) }
    fun protectedBlockEstimate(): Int = regions.size

    companion object {
        val EMPTY = ProtectionMask(emptyList())
    }
}

data class ChunkRiskReport(
    val worldId: UUID,
    val worldName: String,
    val chunkX: Int,
    val chunkZ: Int,
    val risk: RiskLevel,
    val buildScore: Int,
    val damageScore: Int,
    val assetCount: Int,
    val recoverableBlocks: Int,
    val protectedBlocks: Int,
    val skippedBlocks: Int,
    val reasons: List<String>
)

data class AreaScanReport(
    val worldId: UUID,
    val worldName: String,
    val box: BlockBox,
    val chunks: List<ChunkRiskReport>,
    val mask: ProtectionMask
) {
    val lowChunks: Int get() = chunks.count { it.risk == RiskLevel.LOW }
    val mediumChunks: Int get() = chunks.count { it.risk == RiskLevel.MEDIUM }
    val highChunks: Int get() = chunks.count { it.risk == RiskLevel.HIGH }
    val recoverableBlocks: Int get() = chunks.sumOf { it.recoverableBlocks }
    val protectedBlocks: Int get() = chunks.sumOf { it.protectedBlocks }
    val skippedBlocks: Int get() = chunks.sumOf { it.skippedBlocks }
    val buildScore: Int get() = chunks.sumOf { it.buildScore }
    val damageScore: Int get() = chunks.sumOf { it.damageScore }
    val assetCount: Int get() = chunks.sumOf { it.assetCount }
    val highestRisk: RiskLevel get() = when {
        chunks.any { it.risk == RiskLevel.HIGH } -> RiskLevel.HIGH
        chunks.any { it.risk == RiskLevel.MEDIUM } -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}

data class RestoreBlockPlan(
    val x: Int,
    val y: Int,
    val z: Int,
    val targetBlockData: String
)

data class BackupBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val blockData: String
)

data class RestoreJobRecord(
    val jobId: UUID,
    val worldId: UUID,
    val worldName: String,
    val originWorldName: String,
    val mode: RestoreMode,
    val region: BlockBox,
    val creatorUuid: UUID?,
    val status: RestoreJobStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val backupPath: String?,
    val planHash: String?,
    val backupHash: String?,
    val restoreProgress: Int,
    val rollbackProgress: Int,
    val affectedChunks: Int,
    val affectedBlocks: Int,
    val protectedBlocks: Int,
    val skippedBlocks: Int,
    val errorMessage: String?,
    val confirmed: Boolean
) {
    fun transition(
        next: RestoreJobStatus,
        now: Long = System.currentTimeMillis(),
        error: String? = errorMessage,
        backupPath: String? = this.backupPath,
        planHash: String? = this.planHash,
        backupHash: String? = this.backupHash,
        restoreProgress: Int = this.restoreProgress,
        rollbackProgress: Int = this.rollbackProgress,
        affectedBlocks: Int = this.affectedBlocks,
        protectedBlocks: Int = this.protectedBlocks,
        skippedBlocks: Int = this.skippedBlocks,
        confirmed: Boolean = this.confirmed
    ): RestoreJobRecord = copy(
        status = next,
        updatedAt = now,
        errorMessage = error,
        backupPath = backupPath,
        planHash = planHash,
        backupHash = backupHash,
        restoreProgress = restoreProgress,
        rollbackProgress = rollbackProgress,
        affectedBlocks = affectedBlocks,
        protectedBlocks = protectedBlocks,
        skippedBlocks = skippedBlocks,
        confirmed = confirmed
    )
}

data class WildernessSnapshot(
    val assets: Map<UUID, PlayerAssetRecord>,
    val protectedAreas: Map<UUID, ProtectedAreaRecord>,
    val jobs: Map<UUID, RestoreJobRecord>
) {
    companion object {
        val EMPTY = WildernessSnapshot(emptyMap(), emptyMap(), emptyMap())
    }
}
