package com.lifelink.treelifecycle.repository

import com.lifelink.treelifecycle.domain.ManagedTreeRecord
import com.lifelink.treelifecycle.domain.ReplantTaskRecord
import com.lifelink.treelifecycle.domain.RepositorySnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface TreeRepository : AutoCloseable {
    fun loadAsync(): CompletableFuture<RepositorySnapshot>

    fun saveManagedTreeAsync(record: ManagedTreeRecord): CompletableFuture<Void>

    fun removeManagedTreeAsync(id: UUID): CompletableFuture<Void>

    fun saveTaskAsync(record: ReplantTaskRecord): CompletableFuture<Void>

    fun removeTaskAsync(id: UUID): CompletableFuture<Void>

    fun flushAsync(): CompletableFuture<Void>
}
