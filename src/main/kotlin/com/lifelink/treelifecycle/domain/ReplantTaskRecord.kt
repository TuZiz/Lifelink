package com.lifelink.treelifecycle.domain

import java.util.UUID

data class ReplantTaskRecord(
    val id: UUID,
    val lifecycleId: UUID,
    val kind: TreeKind,
    val rootLocation: BlockLocation,
    val saplingLocations: Set<BlockLocation>,
    val status: ReplantTaskStatus,
    val attempts: Int,
    val playerUuid: UUID?,
    val playerName: String?,
    val failureReason: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val version: Long
) {
    fun transition(
        next: ReplantTaskStatus,
        now: Long,
        reason: String? = failureReason,
        attempts: Int = this.attempts
    ): ReplantTaskRecord = copy(
        status = next,
        failureReason = reason,
        attempts = attempts,
        updatedAtEpochMillis = now,
        version = version + 1
    )
}
