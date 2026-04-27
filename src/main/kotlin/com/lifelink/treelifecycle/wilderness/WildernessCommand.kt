package com.lifelink.treelifecycle.wilderness

import com.lifelink.treelifecycle.i18n.MessageService
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import com.lifelink.treelifecycle.util.Permissions
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class WildernessCommand(
    private val service: WildernessRestoreService,
    private val manualProtectionService: ManualProtectionService,
    private val messageService: MessageService,
    private val scheduler: SchedulerAdapter
) {
    private val pos1 = ConcurrentHashMap<UUID, Triple<String, Int, Int>>()
    private val pos1Y = ConcurrentHashMap<UUID, Int>()
    private val pos2 = ConcurrentHashMap<UUID, Triple<String, Int, Int>>()
    private val pos2Y = ConcurrentHashMap<UUID, Int>()

    fun handle(sender: CommandSender, args: List<String>): Boolean {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, "", "help", "?" -> help(sender, args.getOrNull(1))
            "scan" -> scan(sender, args.drop(1))
            "preview" -> preview(sender, args.drop(1))
            "restore" -> restore(sender, args.drop(1))
            "confirm" -> confirm(sender, args.getOrNull(1))
            "cancel" -> cancel(sender, args.getOrNull(1))
            "rollback" -> rollback(sender, args.getOrNull(1))
            "jobs" -> jobs(sender)
            "job" -> job(sender, args.getOrNull(1))
            "protect", "protection" -> protect(sender, args.drop(1))
            "pos1" -> setPos(sender, true)
            "pos2" -> setPos(sender, false)
            else -> help(sender, args.firstOrNull())
        }
        return true
    }

    fun tab(sender: CommandSender, args: List<String>): List<String> = when (args.size) {
        1 -> listOf("help", "scan", "preview", "restore", "confirm", "cancel", "rollback", "jobs", "job", "protect", "pos1", "pos2")
            .filter { it.startsWith(args[0], true) && canSee(sender, it) }
        2 -> when (args[0].lowercase(Locale.ROOT)) {
            "scan" -> listOf("chunk", "radius", "selection").filter { it.startsWith(args[1], true) }
            "preview" -> listOf("chunk", "radius", "selection", "clear").filter { it.startsWith(args[1], true) }
            "restore" -> listOf("chunk", "radius", "surface", "selection").filter { it.startsWith(args[1], true) }
            "protect" -> listOf("chunk", "radius", "pos1", "pos2", "create", "list", "remove").filter { it.startsWith(args[1], true) }
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun scan(sender: CommandSender, args: List<String>) {
        if (!requirePermission(sender, Permissions.WILDERNESS_SCAN)) return
        val player = requirePlayer(sender) ?: return
        val (box, surfaceOnly) = resolveArea(player, args, surface = false) ?: return
        messageService.send(player, "wilderness-scan-start", mapOf("world" to player.world.name, "radius" to radiusText(args)))
        service.scanArea(player.world, box, surfaceOnly) { report ->
            deliver(player) {
                messageService.send(
                    player,
                    "wilderness-scan-finished",
                    mapOf(
                        "low" to report.lowChunks.toString(),
                        "medium" to report.mediumChunks.toString(),
                        "high" to report.highChunks.toString(),
                        "damage" to report.damageScore.toString(),
                        "build" to report.buildScore.toString(),
                        "assets" to report.assetCount.toString(),
                        "recoverable" to report.recoverableBlocks.toString(),
                        "skipped" to report.skippedBlocks.toString()
                    )
                )
                soundByRisk(player, report.highestRisk)
            }
        }
    }

    private fun preview(sender: CommandSender, args: List<String>) {
        if (!requirePermission(sender, Permissions.WILDERNESS_PREVIEW)) return
        val player = requirePlayer(sender) ?: return
        if (args.firstOrNull()?.equals("clear", true) == true) {
            messageService.send(player, "wilderness-preview-cleared")
            messageService.playSound(player, "warning")
            return
        }
        val (box, surfaceOnly) = resolveArea(player, args, surface = false) ?: return
        service.previewArea(player.world, box, surfaceOnly) { report ->
            deliver(player) {
                messageService.send(player, "wilderness-preview-created")
                messageService.playSound(player, "wilderness-preview")
                messageService.send(
                    player,
                    "wilderness-scan-finished",
                    mapOf("low" to report.lowChunks.toString(), "medium" to report.mediumChunks.toString(), "high" to report.highChunks.toString())
                )
            }
        }
    }

    private fun restore(sender: CommandSender, args: List<String>) {
        if (!requirePermission(sender, Permissions.WILDERNESS_RESTORE)) return
        val player = requirePlayer(sender) ?: return
        val mode = when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "chunk" -> RestoreMode.CHUNK
            "radius" -> RestoreMode.RADIUS
            "surface" -> RestoreMode.SURFACE
            "selection" -> RestoreMode.SELECTION
            else -> {
                help(player, "restore")
                return
            }
        }
        val (box, surfaceOnly) = resolveArea(player, args, surface = mode == RestoreMode.SURFACE) ?: return
        service.createRestore(player.uniqueId, player.world, box, mode, surfaceOnly) { job, key ->
            deliver(player) { sendJobResult(player, job, key) }
        }
    }

    private fun confirm(sender: CommandSender, rawJobId: String?) {
        if (!requirePermission(sender, Permissions.WILDERNESS_CONFIRM)) return
        val player = requirePlayer(sender) ?: return
        val jobId = parseJobId(rawJobId, player) ?: return
        service.confirm(jobId) { job, key -> deliver(player) { sendJobResult(player, job, key) } }
    }

    private fun cancel(sender: CommandSender, rawJobId: String?) {
        if (!requirePermission(sender, Permissions.WILDERNESS_RESTORE)) return
        val jobId = parseJobId(rawJobId, sender) ?: return
        val job = service.cancel(jobId)
        if (job == null) messageService.send(sender, "wilderness-job-not-found", mapOf("job_id" to jobId.toString()))
        else messageService.send(sender, "wilderness-job-cancelled", mapOf("job_id" to shortId(job.jobId)))
    }

    private fun rollback(sender: CommandSender, rawJobId: String?) {
        if (!requirePermission(sender, Permissions.WILDERNESS_ROLLBACK)) return
        val player = requirePlayer(sender) ?: return
        val jobId = parseJobId(rawJobId, player) ?: return
        messageService.send(player, "wilderness-rollback-started", mapOf("job_id" to shortId(jobId)))
        service.rollback(jobId) { job, key -> deliver(player) { sendJobResult(player, job, key) } }
    }

    private fun jobs(sender: CommandSender) {
        if (!requirePermission(sender, Permissions.WILDERNESS_SCAN)) return
        val lines = service.jobs().take(8)
        if (lines.isEmpty()) {
            messageService.send(sender, "wilderness-jobs-empty")
            return
        }
        lines.forEach { job ->
            messageService.send(
                sender,
                "wilderness-job-line",
                mapOf("job_id" to shortId(job.jobId), "status" to job.status.name, "mode" to job.mode.name, "blocks" to job.affectedBlocks.toString())
            )
        }
    }

    private fun job(sender: CommandSender, rawJobId: String?) {
        if (!requirePermission(sender, Permissions.WILDERNESS_SCAN)) return
        val jobId = parseJobId(rawJobId, sender) ?: return
        val job = service.job(jobId)
        if (job == null) {
            messageService.send(sender, "wilderness-job-not-found", mapOf("job_id" to rawJobId.orEmpty()))
            return
        }
        messageService.send(
            sender,
            "wilderness-job-detail",
            mapOf(
                "job_id" to shortId(job.jobId),
                "status" to job.status.name,
                "mode" to job.mode.name,
                "blocks" to job.affectedBlocks.toString(),
                "skipped" to job.skippedBlocks.toString(),
                "reason" to (job.errorMessage ?: "-")
            )
        )
    }

    private fun protect(sender: CommandSender, args: List<String>) {
        if (!requirePermission(sender, Permissions.WILDERNESS_PROTECT)) return
        val player = requirePlayer(sender) ?: return
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "chunk" -> {
                val chunk = player.location.chunk
                val area = manualProtectionService.create("chunk-${chunk.x}-${chunk.z}", chunkBox(player), player.uniqueId)
                messageService.send(player, "wilderness-protect-created", mapOf("id" to shortId(area.id), "name" to area.name))
            }
            "radius" -> {
                val radius = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 512) ?: 32
                val area = manualProtectionService.create("radius-$radius-${System.currentTimeMillis()}", radiusBox(player, radius), player.uniqueId)
                messageService.send(player, "wilderness-protect-created", mapOf("id" to shortId(area.id), "name" to area.name))
            }
            "pos1" -> setPos(sender, true)
            "pos2" -> setPos(sender, false)
            "create" -> {
                val name = args.getOrNull(1) ?: "manual-${System.currentTimeMillis()}"
                val box = selectionBox(player) ?: return
                val area = manualProtectionService.create(name, box, player.uniqueId)
                messageService.send(player, "wilderness-protect-created", mapOf("id" to shortId(area.id), "name" to area.name))
            }
            "list" -> manualProtectionService.list().take(10).forEach { area ->
                messageService.send(player, "wilderness-protect-line", mapOf("id" to shortId(area.id), "name" to area.name))
            }
            "remove" -> {
                val id = parseProtectedId(args.getOrNull(1))
                if (id != null && manualProtectionService.remove(id)) {
                    messageService.send(player, "wilderness-protect-removed", mapOf("id" to shortId(id)))
                } else {
                    messageService.send(player, "wilderness-job-not-found", mapOf("job_id" to args.getOrNull(1).orEmpty()))
                }
            }
            else -> help(player, "protect")
        }
    }

    private fun setPos(sender: CommandSender, first: Boolean) {
        val player = requirePlayer(sender) ?: return
        val loc = player.location
        if (first) {
            pos1[player.uniqueId] = Triple(player.world.name, loc.blockX, loc.blockZ)
            pos1Y[player.uniqueId] = loc.blockY
            messageService.send(player, "wilderness-pos-set", mapOf("pos" to "pos1"))
        } else {
            pos2[player.uniqueId] = Triple(player.world.name, loc.blockX, loc.blockZ)
            pos2Y[player.uniqueId] = loc.blockY
            messageService.send(player, "wilderness-pos-set", mapOf("pos" to "pos2"))
        }
    }

    private fun resolveArea(player: Player, args: List<String>, surface: Boolean): Pair<BlockBox, Boolean>? = when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
        "chunk" -> chunkBox(player) to false
        "radius" -> radiusBox(player, args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 512) ?: 32) to false
        "surface" -> radiusBox(player, args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 512) ?: 32) to true
        "selection" -> selectionBox(player)?.let { it to surface }
        else -> {
            help(player, "area")
            null
        }
    }

    private fun chunkBox(player: Player): BlockBox {
        val chunk = player.location.chunk
        return BlockBox(player.world.uid, player.world.name, chunk.x shl 4, player.world.minHeight, chunk.z shl 4,
            (chunk.x shl 4) + 15, player.world.maxHeight - 1, (chunk.z shl 4) + 15)
    }

    private fun radiusBox(player: Player, radius: Int): BlockBox {
        val x = player.location.blockX
        val z = player.location.blockZ
        return BlockBox(player.world.uid, player.world.name, x - radius, player.world.minHeight, z - radius,
            x + radius, player.world.maxHeight - 1, z + radius)
    }

    private fun selectionBox(player: Player): BlockBox? {
        val a = pos1[player.uniqueId]
        val b = pos2[player.uniqueId]
        val ay = pos1Y[player.uniqueId]
        val by = pos2Y[player.uniqueId]
        if (a == null || b == null || ay == null || by == null || a.first != player.world.name || b.first != player.world.name) {
            messageService.send(player, "wilderness-selection-missing")
            return null
        }
        return BlockBox(
            player.world.uid,
            player.world.name,
            min(a.second, b.second),
            max(player.world.minHeight, min(ay, by)),
            min(a.third, b.third),
            max(a.second, b.second),
            min(player.world.maxHeight - 1, max(ay, by)),
            max(a.third, b.third)
        )
    }

    private fun sendJobResult(player: Player, job: RestoreJobRecord, key: String) {
        when (key) {
            "wilderness-restore-completed" -> {
                messageService.send(player, key, mapOf("job_id" to shortId(job.jobId), "blocks" to job.affectedBlocks.toString(), "skipped" to job.skippedBlocks.toString()))
                messageService.playSound(player, "wilderness-restore")
            }
            "wilderness-confirm-required" -> {
                messageService.send(player, "wilderness-restore-created", mapOf("job_id" to shortId(job.jobId)))
                messageService.send(player, key, mapOf("job_id" to shortId(job.jobId)))
                messageService.playSound(player, "warning")
            }
            "wilderness-rollback-completed" -> {
                messageService.send(player, key, mapOf("job_id" to shortId(job.jobId)))
                messageService.playSound(player, "success")
            }
            "wilderness-restore-failed" -> {
                messageService.send(player, key, mapOf("job_id" to shortId(job.jobId), "reason" to (job.errorMessage ?: "unknown")))
                messageService.playSound(player, "error")
            }
            else -> messageService.send(player, key, mapOf("job_id" to shortId(job.jobId)))
        }
    }

    private fun help(sender: CommandSender, topic: String? = null) {
        val keys = when (topic?.lowercase(Locale.ROOT)) {
            "scan" -> listOf("wilderness-help-title", "wilderness-help-scan", "wilderness-help-risk", "wilderness-help-examples")
            "preview" -> listOf("wilderness-help-title", "wilderness-help-preview", "wilderness-help-risk", "wilderness-help-examples")
            "restore" -> listOf("wilderness-help-title", "wilderness-help-restore", "wilderness-help-safety", "wilderness-help-examples")
            "protect", "protection" -> listOf("wilderness-help-title", "wilderness-help-protect", "wilderness-help-selection", "wilderness-help-examples")
            "rollback" -> listOf("wilderness-help-title", "wilderness-help-rollback", "wilderness-help-jobs")
            "jobs", "job" -> listOf("wilderness-help-title", "wilderness-help-jobs", "wilderness-help-rollback")
            "area", "selection", "radius", "chunk" -> listOf("wilderness-help-title", "wilderness-help-area", "wilderness-help-selection", "wilderness-help-examples")
            else -> listOf(
                "wilderness-help-title",
                "wilderness-help-purpose",
                "wilderness-help-flow",
                "wilderness-help-scan",
                "wilderness-help-preview",
                "wilderness-help-restore",
                "wilderness-help-protect",
                "wilderness-help-rollback",
                "wilderness-help-examples"
            )
        }
        keys.forEach { messageService.send(sender, it) }
    }

    private fun requirePermission(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission(Permissions.ADMIN)) return true
        messageService.send(sender, "no-permission")
        return false
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        val player = sender as? Player
        if (player == null) messageService.send(sender, "player-only-command")
        return player
    }

    private fun deliver(player: Player, task: () -> Unit) {
        scheduler.runEntity(player, task)
    }

    private fun soundByRisk(player: Player, risk: RiskLevel) {
        when (risk) {
            RiskLevel.LOW -> messageService.playSound(player, "success")
            RiskLevel.MEDIUM -> messageService.playSound(player, "warning")
            RiskLevel.HIGH -> messageService.playSound(player, "error")
        }
    }

    private fun parseJobId(raw: String?, sender: CommandSender): UUID? {
        if (raw.isNullOrBlank()) {
            messageService.send(sender, "wilderness-command-usage")
            return null
        }
        runCatching { UUID.fromString(raw) }.getOrNull()?.let { return it }
        val matches = service.jobs().filter { it.jobId.toString().startsWith(raw, true) }
        if (matches.size == 1) return matches.first().jobId
        messageService.send(sender, "wilderness-job-not-found", mapOf("job_id" to raw))
        return null
    }

    private fun parseProtectedId(raw: String?): UUID? {
        if (raw.isNullOrBlank()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
            ?: manualProtectionService.list().firstOrNull { it.id.toString().startsWith(raw, true) }?.id
    }

    private fun radiusText(args: List<String>): String = args.getOrNull(1) ?: if (args.firstOrNull().equals("chunk", true)) "chunk" else "selection"
    private fun shortId(id: UUID): String = id.toString().substring(0, 8)
    private fun canSee(sender: CommandSender, command: String): Boolean = when (command) {
        "scan", "jobs", "job" -> sender.hasPermission(Permissions.WILDERNESS_SCAN) || sender.hasPermission(Permissions.ADMIN)
        "preview" -> sender.hasPermission(Permissions.WILDERNESS_PREVIEW) || sender.hasPermission(Permissions.ADMIN)
        "restore", "confirm", "cancel" -> sender.hasPermission(Permissions.WILDERNESS_RESTORE) || sender.hasPermission(Permissions.ADMIN)
        "rollback" -> sender.hasPermission(Permissions.WILDERNESS_ROLLBACK) || sender.hasPermission(Permissions.ADMIN)
        "protect", "pos1", "pos2" -> sender.hasPermission(Permissions.WILDERNESS_PROTECT) || sender.hasPermission(Permissions.ADMIN)
        else -> true
    }
}
