package com.lifelink.treelifecycle.bootstrap

import com.lifelink.treelifecycle.command.LifeLinkCommand
import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.i18n.LangService
import com.lifelink.treelifecycle.i18n.MessageService
import com.lifelink.treelifecycle.listener.FarmlandProtectionListener
import com.lifelink.treelifecycle.listener.PlantReplantListener
import com.lifelink.treelifecycle.listener.TreeLifecycleListener
import com.lifelink.treelifecycle.listener.TreeProtectionListener
import com.lifelink.treelifecycle.recovery.RecoveryService
import com.lifelink.treelifecycle.repository.LocalAsyncRepository
import com.lifelink.treelifecycle.repository.TreeRepository
import com.lifelink.treelifecycle.scheduler.FoliaAwareSchedulerAdapter
import com.lifelink.treelifecycle.scheduler.SchedulerAdapter
import com.lifelink.treelifecycle.service.AdminSaplingModeService
import com.lifelink.treelifecycle.service.ReplantService
import com.lifelink.treelifecycle.service.TreeDetectionService
import com.lifelink.treelifecycle.service.TreeLifecycleService
import com.lifelink.treelifecycle.service.TreeProtectionService
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level

class LifeLinkPlugin : JavaPlugin() {
    private lateinit var ioExecutor: ExecutorService
    private lateinit var scheduler: SchedulerAdapter
    private lateinit var configService: ConfigService
    private lateinit var langService: LangService
    private lateinit var messageService: MessageService
    private var repository: TreeRepository? = null
    private var recoveryService: RecoveryService? = null

    override fun onEnable() {
        ioExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "LifeLink-Config-IO").apply { isDaemon = true }
        }
        scheduler = FoliaAwareSchedulerAdapter(this)
        configService = ConfigService(this, ioExecutor)
        langService = LangService(this, configService, ioExecutor)
        messageService = MessageService(this, langService)

        configService.loadAsync()
            .thenCompose { config ->
                langService.loadAsync(config.language.default, config.messages.prefix)
                    .thenApply { config }
            }
            .thenCompose { config ->
                val stateFile = dataFolder.toPath().resolve(config.persistence.fileName)
                val repo = LocalAsyncRepository(stateFile, config.persistence.flushDelayMillis, logger)
                repository = repo
                repo.loadAsync().thenApply { snapshot -> config to snapshot }
            }
            .whenComplete { result, error ->
                if (error != null) {
                    logger.log(Level.SEVERE, "LifeLink failed to load async configuration/state", error)
                    scheduler.runGlobal { server.pluginManager.disablePlugin(this) }
                    return@whenComplete
                }
                scheduler.runGlobal {
                    if (!isEnabled) return@runGlobal
                    finishEnable(result.first, result.second)
                }
            }
    }

    private fun finishEnable(
        config: com.lifelink.treelifecycle.config.AppConfig,
        snapshot: com.lifelink.treelifecycle.domain.RepositorySnapshot
    ) {
        val repo = repository ?: error("Repository was not initialized")
        val detectionService = TreeDetectionService(configService)
        val lifecycleService = TreeLifecycleService(repo, logger)
        lifecycleService.loadSnapshot(snapshot)
        val protectionService = TreeProtectionService(configService, lifecycleService)
        val replantService = ReplantService(configService, lifecycleService, scheduler, logger)
        val recovery = RecoveryService(configService, lifecycleService, detectionService, replantService, scheduler, logger)
        val adminSaplingModeService = AdminSaplingModeService()
        recoveryService = recovery

        server.pluginManager.registerEvents(
            TreeLifecycleListener(
                configService,
                detectionService,
                lifecycleService,
                protectionService,
                replantService,
                messageService,
                scheduler,
                adminSaplingModeService
            ),
            this
        )
        server.pluginManager.registerEvents(TreeProtectionListener(protectionService, messageService), this)
        server.pluginManager.registerEvents(FarmlandProtectionListener(configService), this)
        server.pluginManager.registerEvents(PlantReplantListener(configService, messageService), this)

        val command = LifeLinkCommand(
            configService,
            langService,
            messageService,
            scheduler,
            adminSaplingModeService,
            { recoveryService },
            logger
        )
        getCommand("lifelink")?.setExecutor(command)
        getCommand("lifelink")?.tabCompleter = command

        if (config.replant.recoveryOnStartup) {
            val queued = recovery.recoverStartup()
            logger.info("LifeLink recovery queued $queued checks.")
        }
        logger.info("LifeLink enabled. Folia scheduler mode: ${scheduler.folia}")
    }

    override fun onDisable() {
        runCatching { repository?.close() }
        runCatching { messageService.close() }
        runCatching { scheduler.close() }
        if (::ioExecutor.isInitialized) {
            ioExecutor.shutdown()
        }
    }
}
