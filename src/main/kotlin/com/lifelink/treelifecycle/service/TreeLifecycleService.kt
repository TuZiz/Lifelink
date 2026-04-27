package com.lifelink.treelifecycle.service

import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.LifecycleState
import com.lifelink.treelifecycle.domain.ManagedTreeRecord
import com.lifelink.treelifecycle.domain.ReplantTaskRecord
import com.lifelink.treelifecycle.domain.ReplantTaskStatus
import com.lifelink.treelifecycle.domain.RepositorySnapshot
import com.lifelink.treelifecycle.domain.TreeSnapshot
import com.lifelink.treelifecycle.repository.TreeRepository
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs

class TreeLifecycleService(
    private val repository: TreeRepository,
    private val logger: Logger
) {
    private val loaded = AtomicBoolean(false)
    private val treesById = ConcurrentHashMap<UUID, ManagedTreeRecord>()
    private val saplingIndex = ConcurrentHashMap<BlockLocation, UUID>()
    private val logIndex = ConcurrentHashMap<BlockLocation, UUID>()
    private val tasksById = ConcurrentHashMap<UUID, ReplantTaskRecord>()
    private val activeLocations = ConcurrentHashMap.newKeySet<BlockLocation>()
    private val reservationMutex = Any()

    fun isLoaded(): Boolean = loaded.get()

    fun loadSnapshot(snapshot: RepositorySnapshot) {
        treesById.clear()
        saplingIndex.clear()
        logIndex.clear()
        tasksById.clear()
        activeLocations.clear()

        snapshot.managedTrees.values.forEach(::putTreeInMemory)
        snapshot.tasks.values.forEach { task ->
            tasksById[task.id] = task
            if (!task.status.isTerminal()) reserveLocations(task.saplingLocations + task.rootLocation)
        }
        loaded.set(true)
    }

    fun systemSaplingAt(location: BlockLocation): ManagedTreeRecord? =
        saplingIndex[location]?.let(treesById::get)?.takeIf { it.lifecycleState == LifecycleState.SYSTEM_PLANTED_SAPLING }

    fun playerSaplingAt(location: BlockLocation): ManagedTreeRecord? =
        saplingIndex[location]?.let(treesById::get)?.takeIf { it.lifecycleState == LifecycleState.PLAYER_PLANTED_SAPLING }

    fun managedTreeAt(location: BlockLocation): ManagedTreeRecord? =
        logIndex[location]?.let(treesById::get)?.takeIf { it.lifecycleState == LifecycleState.SYSTEM_MANAGED_TREE }

    fun playerTreeAt(location: BlockLocation): ManagedTreeRecord? =
        logIndex[location]?.let(treesById::get)?.takeIf { it.lifecycleState == LifecycleState.PLAYER_PLANTED_TREE }

    fun task(taskId: UUID): ReplantTaskRecord? = tasksById[taskId]

    fun activeTasks(): List<ReplantTaskRecord> =
        tasksById.values.filter { !it.status.isTerminal() }

    fun allTasks(): List<ReplantTaskRecord> = tasksById.values.toList()

    fun allTrees(): List<ManagedTreeRecord> = treesById.values.toList()

    fun beginReplantTask(snapshot: TreeSnapshot, player: Player?, existingManagedTree: ManagedTreeRecord?): BeginTaskResult {
        val reservedLocations = snapshot.saplingLocations + snapshot.root
        if (!reserveLocations(reservedLocations)) return BeginTaskResult.Busy

        val now = System.currentTimeMillis()
        val lifecycleId = existingManagedTree?.id ?: UUID.randomUUID()
        val task = ReplantTaskRecord(
            id = UUID.randomUUID(),
            lifecycleId = lifecycleId,
            kind = snapshot.kind,
            rootLocation = snapshot.root,
            saplingLocations = snapshot.saplingLocations,
            status = ReplantTaskStatus.RESERVED,
            attempts = 0,
            playerUuid = player?.uniqueId,
            playerName = player?.name,
            failureReason = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            version = 0
        )

        // 托管树进入砍伐阶段后立即从运行时索引移除，避免玩家从树根合法触发后继续砍树时被旧索引误拦截。
        if (existingManagedTree != null) {
            removeTreeFromMemory(existingManagedTree.id)
            repository.removeManagedTreeAsync(existingManagedTree.id).logFailure("remove managed tree ${existingManagedTree.id}")
        }

        tasksById[task.id] = task
        repository.saveTaskAsync(task).logFailure("persist task ${task.id}")
        return BeginTaskResult.Started(task)
    }

    fun transitionTask(
        taskId: UUID,
        allowed: Set<ReplantTaskStatus>,
        next: ReplantTaskStatus,
        reason: String? = null,
        attempts: Int? = null
    ): ReplantTaskRecord? {
        val now = System.currentTimeMillis()
        var changed: ReplantTaskRecord? = null

        tasksById.computeIfPresent(taskId) { _, current ->
            if (current.status.isTerminal() || current.status !in allowed) {
                current
            } else {
                current.transition(
                    next = next,
                    now = now,
                    reason = reason,
                    attempts = attempts ?: current.attempts
                ).also { changed = it }
            }
        }

        changed?.let {
            repository.saveTaskAsync(it).logFailure("persist task transition ${it.id} -> ${it.status}")
        }
        return changed
    }

    fun saveTask(task: ReplantTaskRecord): CompletableFuture<Void> {
        tasksById[task.id] = task
        return repository.saveTaskAsync(task).logFailure("persist task ${task.id}")
    }

    fun registerRecoveryTask(task: ReplantTaskRecord): Boolean {
        if (!reserveLocations(task.saplingLocations + task.rootLocation)) return false
        tasksById[task.id] = task
        repository.saveTaskAsync(task).logFailure("persist recovery task ${task.id}")
        return true
    }

    fun recordPlayerSapling(kind: com.lifelink.treelifecycle.domain.TreeKind, location: BlockLocation): ManagedTreeRecord {
        val existing = playerSaplingAt(location)
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val record = ManagedTreeRecord(
            id = UUID.randomUUID(),
            kind = kind,
            lifecycleState = LifecycleState.PLAYER_PLANTED_SAPLING,
            saplingLocations = setOf(location),
            logLocations = emptySet(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            version = 0
        )
        putTreeInMemory(record)
        repository.saveManagedTreeAsync(record).logFailure("persist player sapling ${record.id}")
        return record
    }

    fun recordSystemSapling(kind: com.lifelink.treelifecycle.domain.TreeKind, location: BlockLocation): ManagedTreeRecord {
        saplingIndex[location]?.let { existingId ->
            val existing = treesById[existingId]
            if (existing?.lifecycleState == LifecycleState.SYSTEM_PLANTED_SAPLING && existing.kind == kind) {
                return existing
            }
            removeTreeFromMemory(existingId)
            repository.removeManagedTreeAsync(existingId).logFailure("replace sapling record $existingId")
        }

        val now = System.currentTimeMillis()
        val record = ManagedTreeRecord(
            id = UUID.randomUUID(),
            kind = kind,
            lifecycleState = LifecycleState.SYSTEM_PLANTED_SAPLING,
            saplingLocations = setOf(location),
            logLocations = emptySet(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            version = 0
        )
        putTreeInMemory(record)
        repository.saveManagedTreeAsync(record).logFailure("persist admin managed sapling ${record.id}")
        return record
    }

    fun recordSystemSapling(task: ReplantTaskRecord): ManagedTreeRecord {
        val now = System.currentTimeMillis()
        val record = ManagedTreeRecord(
            id = task.lifecycleId,
            kind = task.kind,
            lifecycleState = LifecycleState.SYSTEM_PLANTED_SAPLING,
            saplingLocations = task.saplingLocations,
            logLocations = emptySet(),
            createdAtEpochMillis = task.createdAtEpochMillis,
            updatedAtEpochMillis = now,
            version = 0
        )
        putTreeInMemory(record)
        repository.saveManagedTreeAsync(record).logFailure("persist system sapling ${record.id}")
        return record
    }

    fun promoteSaplingToManaged(saplingLocation: BlockLocation, snapshot: TreeSnapshot): Boolean =
        promoteSaplingToManagedFromBlocks(saplingLocation, snapshot.logLocations)

    fun promoteSaplingToManagedFromBlocks(saplingLocation: BlockLocation, logLocations: Set<BlockLocation>): Boolean {
        val current = systemSaplingAt(saplingLocation) ?: return false
        if (logLocations.isEmpty()) return false

        nearbySystemSaplings(saplingLocation, current.kind).forEach {
            removeTreeFromMemory(it.id)
            if (it.id != current.id) {
                repository.removeManagedTreeAsync(it.id).logFailure("remove merged system sapling ${it.id}")
            }
        }

        val now = System.currentTimeMillis()
        val managed = current.withState(
            state = LifecycleState.SYSTEM_MANAGED_TREE,
            saplings = emptySet(),
            logs = logLocations,
            now = now
        )
        replaceTreeInMemory(managed)
        repository.saveManagedTreeAsync(managed).logFailure("persist managed tree ${managed.id}")
        return true
    }

    fun promotePlayerSaplingToTree(saplingLocation: BlockLocation, snapshot: TreeSnapshot): Boolean =
        promotePlayerSaplingToTreeFromBlocks(saplingLocation, snapshot.logLocations)

    fun promotePlayerSaplingToTreeFromBlocks(saplingLocation: BlockLocation, logLocations: Set<BlockLocation>): Boolean {
        val current = playerSaplingAt(saplingLocation) ?: return false
        if (logLocations.isEmpty()) return false

        // 2x2 树苗成长时，周围几个玩家树苗记录会一起消失；统一移除后只保留一条玩家种植树记录。
        nearbyPlayerSaplings(saplingLocation, current.kind).forEach {
            removeTreeFromMemory(it.id)
            if (it.id != current.id) {
                repository.removeManagedTreeAsync(it.id).logFailure("remove merged player sapling ${it.id}")
            }
        }
        val now = System.currentTimeMillis()
        val playerTree = current.withState(
            state = LifecycleState.PLAYER_PLANTED_TREE,
            saplings = emptySet(),
            logs = logLocations,
            now = now
        )
        putTreeInMemory(playerTree)
        repository.saveManagedTreeAsync(playerTree).logFailure("persist player planted tree ${playerTree.id}")
        return true
    }

    fun completeTask(task: ReplantTaskRecord, status: ReplantTaskStatus, reason: String? = null) {
        val completed = task.transition(status, System.currentTimeMillis(), reason)
        tasksById[completed.id] = completed
        repository.saveTaskAsync(completed).logFailure("persist completed task ${completed.id}")
        releaseLocations(completed.saplingLocations + completed.rootLocation)
    }

    fun removeTree(id: UUID) {
        removeTreeFromMemory(id)
        repository.removeManagedTreeAsync(id).logFailure("remove tree $id")
    }

    fun removeTreeIfRoot(id: UUID, brokenLocation: BlockLocation) {
        val record = treesById[id] ?: return
        val root = record.logLocations.minWithOrNull(compareBy({ it.y }, { it.x }, { it.z })) ?: return
        if (root == brokenLocation) removeTree(id)
    }

    fun removeTask(id: UUID) {
        val task = tasksById.remove(id)
        if (task != null) releaseLocations(task.saplingLocations + task.rootLocation)
        repository.removeTaskAsync(id).logFailure("remove task $id")
    }

    private fun putTreeInMemory(record: ManagedTreeRecord) {
        treesById[record.id] = record
        record.saplingLocations.forEach { saplingIndex[it] = record.id }
        record.logLocations.forEach { logIndex[it] = record.id }
    }

    private fun replaceTreeInMemory(record: ManagedTreeRecord) {
        removeTreeFromMemory(record.id)
        putTreeInMemory(record)
    }

    private fun removeTreeFromMemory(id: UUID) {
        val previous = treesById.remove(id) ?: return
        previous.saplingLocations.forEach { saplingIndex.remove(it, id) }
        previous.logLocations.forEach { logIndex.remove(it, id) }
    }

    private fun nearbyPlayerSaplings(location: BlockLocation, kind: com.lifelink.treelifecycle.domain.TreeKind): List<ManagedTreeRecord> =
        saplingIndex.entries.asSequence()
            .filter { (saplingLocation, _) ->
                saplingLocation.worldId == location.worldId &&
                    saplingLocation.y == location.y &&
                    abs(saplingLocation.x - location.x) <= 1 &&
                    abs(saplingLocation.z - location.z) <= 1
            }
            .mapNotNull { (_, id) -> treesById[id] }
            .filter { it.lifecycleState == LifecycleState.PLAYER_PLANTED_SAPLING && it.kind == kind }
            .distinctBy { it.id }
            .toList()

    private fun nearbySystemSaplings(location: BlockLocation, kind: com.lifelink.treelifecycle.domain.TreeKind): List<ManagedTreeRecord> =
        saplingIndex.entries.asSequence()
            .filter { (saplingLocation, _) ->
                saplingLocation.worldId == location.worldId &&
                    saplingLocation.y == location.y &&
                    abs(saplingLocation.x - location.x) <= 1 &&
                    abs(saplingLocation.z - location.z) <= 1
            }
            .mapNotNull { (_, id) -> treesById[id] }
            .filter { it.lifecycleState == LifecycleState.SYSTEM_PLANTED_SAPLING && it.kind == kind }
            .distinctBy { it.id }
            .toList()

    private fun reserveLocations(locations: Set<BlockLocation>): Boolean = synchronized(reservationMutex) {
        if (locations.any { it in activeLocations }) return@synchronized false
        activeLocations.addAll(locations)
        true
    }

    private fun releaseLocations(locations: Set<BlockLocation>) = synchronized(reservationMutex) {
        locations.forEach(activeLocations::remove)
    }

    private fun CompletableFuture<Void>.logFailure(action: String): CompletableFuture<Void> =
        whenComplete { _, error ->
            if (error != null) logger.log(Level.SEVERE, "Repository failure while trying to $action", error)
        }

    sealed interface BeginTaskResult {
        data class Started(val task: ReplantTaskRecord) : BeginTaskResult
        data object Busy : BeginTaskResult
    }
}
