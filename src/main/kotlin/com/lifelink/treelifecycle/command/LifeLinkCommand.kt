package com.lifelink.treelifecycle.command

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.i18n.LangService
import com.lifelink.treelifecycle.i18n.MessageService
import com.lifelink.treelifecycle.recovery.RecoveryService
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import com.lifelink.treelifecycle.service.AdminSaplingModeService
import com.lifelink.treelifecycle.util.Permissions
import com.lifelink.treelifecycle.wilderness.WildernessCommand
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

class LifeLinkCommand(
    private val configService: ConfigService,
    private val langService: LangService,
    private val messageService: MessageService,
    private val scheduler: SchedulerAdapter,
    private val adminSaplingModeService: AdminSaplingModeService,
    private val wildernessCommand: WildernessCommand,
    private val recoveryServiceProvider: () -> RecoveryService?,
    private val logger: Logger
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, "", "help", "?" -> help(sender)
            "reload" -> reload(sender)
            "recover" -> recover(sender)
            "saplingmode" -> saplingMode(sender, args.getOrNull(1))
            "wilderness", "wild", "restore" -> wildernessCommand.handle(sender, args.drop(1))
            "status", "stats" -> status(sender)
            "inspect" -> inspect(sender)
            "debug" -> debug(sender, args.getOrNull(1))
            else -> help(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        return when (args.size) {
            1 -> availableSubcommands(sender)
                .filter { it.startsWith(args[0], ignoreCase = true) }
                .toMutableList()
            2 -> if (args[0].equals("saplingmode", ignoreCase = true) && canUseSaplingMode(sender)) {
                listOf("on", "off", "status")
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                    .toMutableList()
            } else if (args[0].equals("wilderness", ignoreCase = true) || args[0].equals("wild", ignoreCase = true)) {
                wildernessCommand.tab(sender, args.drop(1)).toMutableList()
            } else {
                mutableListOf()
            }
            else -> if (args[0].equals("wilderness", ignoreCase = true) || args[0].equals("wild", ignoreCase = true)) {
                wildernessCommand.tab(sender, args.drop(1)).toMutableList()
            } else {
                mutableListOf()
            }
        }
    }

    private fun reload(sender: CommandSender) {
        if (!sender.hasPermission(Permissions.RELOAD) && !sender.hasPermission(Permissions.ADMIN)) {
            messageService.send(sender, "no-permission")
            return
        }

        messageService.send(sender, "reload-started")
        configService.loadAsync()
            .thenCompose { config -> langService.loadAsync(config.language.default, config.messages.prefix) }
            .whenComplete { _, error ->
                scheduler.runGlobal {
                    if (error != null) {
                        logger.log(Level.SEVERE, "LifeLink reload failed", error)
                        messageService.send(sender, "reload-failed", mapOf("reason" to (error.message ?: error.javaClass.simpleName)))
                    } else {
                        messageService.send(sender, "reload-success")
                    }
                }
            }
    }

    private fun recover(sender: CommandSender) {
        if (!sender.hasPermission(Permissions.RECOVER) && !sender.hasPermission(Permissions.ADMIN)) {
            messageService.send(sender, "no-permission")
            return
        }

        val recoveryService = recoveryServiceProvider()
        if (recoveryService == null) {
            messageService.send(sender, "plugin-loading")
            return
        }

        val queued = recoveryService.recoverNow()
        messageService.send(sender, "recovery-queued", mapOf("reason" to queued.toString()))
    }

    private fun saplingMode(sender: CommandSender, rawMode: String?) {
        if (!canUseSaplingMode(sender)) {
            messageService.send(sender, "no-permission")
            return
        }
        val player = sender as? Player
        if (player == null) {
            messageService.send(sender, "player-only-command")
            return
        }

        val enabled = when (rawMode?.lowercase(Locale.ROOT)) {
            null, "", "toggle" -> adminSaplingModeService.toggle(player)
            "on", "enable", "enabled", "true" -> adminSaplingModeService.setEnabled(player, true)
            "off", "disable", "disabled", "false" -> adminSaplingModeService.setEnabled(player, false)
            "status" -> adminSaplingModeService.isEnabled(player)
            else -> {
                messageService.send(sender, "sapling-mode-usage")
                return
            }
        }

        val key = if (enabled) "sapling-mode-enabled" else "sapling-mode-disabled"
        messageService.send(player, key)
    }

    private fun availableSubcommands(sender: CommandSender): List<String> =
        buildList {
            add("help")
            if (sender.hasPermission(Permissions.RELOAD) || sender.hasPermission(Permissions.ADMIN)) add("reload")
            if (sender.hasPermission(Permissions.RECOVER) || sender.hasPermission(Permissions.ADMIN)) add("recover")
            if (canUseSaplingMode(sender)) add("saplingmode")
            if (sender.hasPermission(Permissions.WILDERNESS_SCAN) || sender.hasPermission(Permissions.ADMIN)) add("wilderness")
            if (sender.hasPermission(Permissions.STATS) || sender.hasPermission(Permissions.ADMIN)) add("status")
            if (sender.hasPermission(Permissions.STATS) || sender.hasPermission(Permissions.ADMIN)) add("stats")
            if (sender.hasPermission(Permissions.INSPECT) || sender.hasPermission(Permissions.ADMIN)) add("inspect")
        }

    private fun canUseSaplingMode(sender: CommandSender): Boolean =
        sender.hasPermission(Permissions.ADMIN_SAPLING_MODE) || sender.hasPermission(Permissions.ADMIN)

    private fun help(sender: CommandSender) {
        listOf(
            "help-title",
            "help-tree",
            "help-saplingmode",
            "help-wilderness",
            "help-wilderness-flow",
            "help-admin",
            "help-next"
        ).forEach { messageService.send(sender, it) }
    }

    private fun status(sender: CommandSender) {
        if (!sender.hasPermission(Permissions.STATS) && !sender.hasPermission(Permissions.ADMIN)) {
            messageService.send(sender, "no-permission")
            return
        }
        messageService.send(sender, "status-summary")
    }

    private fun inspect(sender: CommandSender) {
        if (!sender.hasPermission(Permissions.INSPECT) && !sender.hasPermission(Permissions.ADMIN)) {
            messageService.send(sender, "no-permission")
            return
        }
        messageService.send(sender, "inspect-usage")
    }

    private fun debug(sender: CommandSender, target: String?) {
        if (!sender.hasPermission(Permissions.WILDERNESS_DEBUG) && !sender.hasPermission(Permissions.ADMIN)) {
            messageService.send(sender, "no-permission")
            return
        }
        messageService.send(sender, "debug-summary", mapOf("target" to (target ?: "scheduler"), "folia" to scheduler.folia.toString()))
    }
}
