package com.lifelink.treelifecycle.domain

import java.util.UUID

data class RepositorySnapshot(
    val managedTrees: Map<UUID, ManagedTreeRecord>,
    val tasks: Map<UUID, ReplantTaskRecord>
) {
    companion object {
        val EMPTY = RepositorySnapshot(emptyMap(), emptyMap())
    }
}
