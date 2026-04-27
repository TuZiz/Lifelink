package com.lifelink.treelifecycle.scheduler

import org.bukkit.World
import org.bukkit.entity.Entity

interface SchedulerAdapter : AutoCloseable {
    val folia: Boolean

    fun runGlobal(task: () -> Unit): ScheduledTaskHandle

    fun runGlobalLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle

    fun runAt(world: World, chunkX: Int, chunkZ: Int, task: () -> Unit): ScheduledTaskHandle

    fun runAtLater(world: World, chunkX: Int, chunkZ: Int, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle

    fun runEntity(entity: Entity, task: () -> Unit): ScheduledTaskHandle

    fun runAsync(task: () -> Unit): ScheduledTaskHandle
}
