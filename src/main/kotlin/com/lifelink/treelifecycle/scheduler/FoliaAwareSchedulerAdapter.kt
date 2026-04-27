package com.lifelink.treelifecycle.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer
import java.util.logging.Level

class FoliaAwareSchedulerAdapter(
    private val plugin: JavaPlugin
) : SchedulerAdapter {
    private val fallback = PaperSchedulerAdapter(plugin)
    private val asyncExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "LifeLink-Folia-Async").apply { isDaemon = true }
    }
    private val foliaRuntime: Boolean = detectFolia()

    override val folia: Boolean = foliaRuntime

    override fun runGlobal(task: () -> Unit): ScheduledTaskHandle {
        if (!foliaRuntime) return fallback.runGlobal(task)
        return invokeFoliaGlobal("run", task)
            ?: invokeFoliaGlobalExecute(task)
            ?: failFoliaScheduler("global scheduler")
    }

    override fun runGlobalLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle {
        if (!foliaRuntime) return fallback.runGlobalLater(delayTicks, task)
        return invokeFoliaGlobal("runDelayed", task, delayTicks.coerceAtLeast(1L))
            ?: failFoliaScheduler("global delayed scheduler")
    }

    override fun runAt(world: World, chunkX: Int, chunkZ: Int, task: () -> Unit): ScheduledTaskHandle {
        if (!foliaRuntime) return fallback.runAt(world, chunkX, chunkZ, task)
        return invokeFoliaRegion("run", world, chunkX, chunkZ, task) ?: invokeFoliaRegionExecute(world, chunkX, chunkZ, task)
            ?: failFoliaScheduler("region scheduler")
    }

    override fun runAtLater(world: World, chunkX: Int, chunkZ: Int, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle {
        if (!foliaRuntime) return fallback.runAtLater(world, chunkX, chunkZ, delayTicks, task)
        return invokeFoliaRegion("runDelayed", world, chunkX, chunkZ, task, delayTicks.coerceAtLeast(1L))
            ?: failFoliaScheduler("region delayed scheduler")
    }

    override fun runEntity(entity: Entity, task: () -> Unit): ScheduledTaskHandle {
        if (!foliaRuntime) return fallback.runEntity(entity, task)
        return runCatching {
            val scheduler = entity.javaClass.getMethod("getScheduler").invoke(entity)
            val method = scheduler.javaClass.methods.firstOrNull {
                it.name == "run" && it.parameterTypes.size == 3
            } ?: return@runCatching null
            val result = method.invoke(scheduler, plugin, Consumer<Any> { task() }, Runnable { })
            ReflectiveTaskHandle(result)
        }.onFailure {
            plugin.logger.log(Level.WARNING, "Failed to use Folia entity scheduler", it)
        }.getOrNull() ?: failFoliaScheduler("entity scheduler")
    }

    override fun runAsync(task: () -> Unit): ScheduledTaskHandle {
        val future: Future<*> = asyncExecutor.submit(task)
        return ScheduledTaskHandle { future.cancel(false) }
    }

    override fun close() {
        asyncExecutor.shutdownNow()
        fallback.close()
    }

    private fun detectFolia(): Boolean =
        runCatching {
            Bukkit::class.java.getMethod("getRegionScheduler")
            Bukkit::class.java.getMethod("getGlobalRegionScheduler")
            true
        }.getOrDefault(false)

    private fun invokeFoliaGlobal(methodName: String, task: () -> Unit, vararg extra: Any): ScheduledTaskHandle? =
        runCatching {
            val scheduler = Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
            val args = (listOf<Any>(plugin, Consumer<Any> { task() }) + extra).toTypedArray()
            invokeCompatible(scheduler, methodName, args)?.let(::ReflectiveTaskHandle)
        }.onFailure {
            plugin.logger.log(Level.WARNING, "Failed to invoke Folia global scheduler $methodName", it)
        }.getOrNull()

    private fun invokeFoliaGlobalExecute(task: () -> Unit): ScheduledTaskHandle? =
        runCatching {
            val scheduler = Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
            invokeCompatible(scheduler, "execute", arrayOf(plugin, Runnable(task)))
            ScheduledTaskHandle.NOOP
        }.getOrNull()

    private fun invokeFoliaRegion(
        methodName: String,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        task: () -> Unit,
        vararg extra: Any
    ): ScheduledTaskHandle? =
        runCatching {
            val scheduler = Bukkit::class.java.getMethod("getRegionScheduler").invoke(null)
            val consumer = Consumer<Any> { task() }
            val worldArgs = (listOf<Any>(plugin, world, chunkX, chunkZ, consumer) + extra).toTypedArray()
            val locationArgs = (
                listOf<Any>(
                    plugin,
                    Location(world, (chunkX shl 4).toDouble(), 0.0, (chunkZ shl 4).toDouble()),
                    consumer
                ) + extra
                ).toTypedArray()
            val result = invokeCompatible(scheduler, methodName, worldArgs)
                ?: invokeCompatible(scheduler, methodName, locationArgs)
            result?.let(::ReflectiveTaskHandle)
        }.onFailure {
            plugin.logger.log(Level.WARNING, "Failed to invoke Folia region scheduler $methodName", it)
        }.getOrNull()

    private fun invokeFoliaRegionExecute(world: World, chunkX: Int, chunkZ: Int, task: () -> Unit): ScheduledTaskHandle? =
        runCatching {
            val scheduler = Bukkit::class.java.getMethod("getRegionScheduler").invoke(null)
            val worldArgs = arrayOf<Any>(plugin, world, chunkX, chunkZ, Runnable(task))
            val locationArgs = arrayOf<Any>(plugin, Location(world, (chunkX shl 4).toDouble(), 0.0, (chunkZ shl 4).toDouble()), Runnable(task))
            invokeCompatible(scheduler, "execute", worldArgs) ?: invokeCompatible(scheduler, "execute", locationArgs)
            ScheduledTaskHandle.NOOP
        }.getOrNull()

    private fun invokeCompatible(target: Any, name: String, args: Array<Any>): Any? {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == name && method.parameterTypes.size == args.size && method.accepts(args)
        } ?: return null
        return method.invoke(target, *args)
    }

    private fun Method.accepts(args: Array<Any>): Boolean =
        parameterTypes.zip(args).all { (type, arg) ->
            when {
                type.isPrimitive -> primitiveAccepts(type, arg)
                else -> type.isAssignableFrom(arg.javaClass)
            }
        }

    private fun primitiveAccepts(type: Class<*>, arg: Any): Boolean =
        (type == java.lang.Long.TYPE && arg is Long) ||
            (type == java.lang.Integer.TYPE && arg is Int) ||
            (type == java.lang.Boolean.TYPE && arg is Boolean)

    private fun failFoliaScheduler(context: String): ScheduledTaskHandle {
        val message = "Folia $context reflection failed; refusing to fall back to BukkitScheduler."
        plugin.logger.severe(message)
        throw IllegalStateException(message)
    }

    private class ReflectiveTaskHandle(private val task: Any?) : ScheduledTaskHandle {
        override fun cancel() {
            val handle = task ?: return
            runCatching {
                handle.javaClass.methods.firstOrNull { it.name == "cancel" && it.parameterTypes.isEmpty() }?.invoke(handle)
            }
        }
    }
}
