package com.lifelink.treelifecycle.scheduler

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class PaperSchedulerAdapter(
    private val plugin: JavaPlugin
) : SchedulerAdapter {
    private val asyncExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "LifeLink-Paper-Async").apply { isDaemon = true }
    }

    override val folia: Boolean = false

    override fun runGlobal(task: () -> Unit): ScheduledTaskHandle {
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, Runnable(task))
        return ScheduledTaskHandle { bukkitTask.cancel() }
    }

    override fun runGlobalLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle {
        val bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable(task), delayTicks.coerceAtLeast(1L))
        return ScheduledTaskHandle { bukkitTask.cancel() }
    }

    override fun runAt(world: World, chunkX: Int, chunkZ: Int, task: () -> Unit): ScheduledTaskHandle =
        runGlobal(task)

    override fun runAtLater(world: World, chunkX: Int, chunkZ: Int, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        runGlobalLater(delayTicks, task)

    override fun runEntity(entity: Entity, task: () -> Unit): ScheduledTaskHandle =
        runGlobal(task)

    override fun runAsync(task: () -> Unit): ScheduledTaskHandle {
        val future: Future<*> = asyncExecutor.submit(task)
        return ScheduledTaskHandle { future.cancel(false) }
    }

    override fun close() {
        asyncExecutor.shutdownNow()
    }
}
