package com.lifelink.treelifecycle.domain

data class TreeSnapshot(
    val kind: TreeKind,
    val root: BlockLocation,
    val logLocations: Set<BlockLocation>,
    val leafLocations: Set<BlockLocation>,
    val saplingLocations: Set<BlockLocation>,
    val detectedAtEpochMillis: Long
) {
    fun containsLog(location: BlockLocation): Boolean = location in logLocations
}
