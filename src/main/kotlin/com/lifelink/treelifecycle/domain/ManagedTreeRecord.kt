package com.lifelink.treelifecycle.domain

import java.util.UUID

data class ManagedTreeRecord(
    val id: UUID,
    val kind: TreeKind,
    val lifecycleState: LifecycleState,
    val saplingLocations: Set<BlockLocation>,
    val logLocations: Set<BlockLocation>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val version: Long
) {
    fun primaryLocation(): BlockLocation =
        saplingLocations.minWithOrNull(locationComparator)
            ?: logLocations.minWithOrNull(locationComparator)
            ?: error("ManagedTreeRecord $id has no locations")

    fun withState(
        state: LifecycleState,
        saplings: Set<BlockLocation>,
        logs: Set<BlockLocation>,
        now: Long
    ): ManagedTreeRecord = copy(
        lifecycleState = state,
        saplingLocations = saplings,
        logLocations = logs,
        updatedAtEpochMillis = now,
        version = version + 1
    )

    companion object {
        private val locationComparator = compareBy<BlockLocation>({ it.worldName }, { it.y }, { it.x }, { it.z })
    }
}
