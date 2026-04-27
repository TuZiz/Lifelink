package com.lifelink.treelifecycle.config

import com.lifelink.treelifecycle.domain.FailureStrategy
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class ConfigService(
    private val plugin: JavaPlugin,
    private val ioExecutor: Executor
) {
    private val current = AtomicReference(AppConfig.defaults())

    fun current(): AppConfig = current.get()

    fun loadAsync(): CompletableFuture<AppConfig> =
        CompletableFuture.supplyAsync({
            ensureResource("config.yml", plugin.dataFolder.toPath().resolve("config.yml"))
            val yaml = YamlConfiguration.loadConfiguration(plugin.dataFolder.resolve("config.yml"))
            val loaded = parse(yaml)
            current.set(loaded)
            loaded
        }, ioExecutor)

    private fun parse(yaml: YamlConfiguration): AppConfig {
        val defaults = AppConfig.defaults()
        val allowedTools = yaml.getStringList("harvest.allowed-tools")
            .mapNotNull { materialName ->
                Material.matchMaterial(materialName).also {
                    if (it == null) plugin.logger.warning("Unknown harvest tool in config: $materialName")
                }
            }
            .toSet()
            .ifEmpty { defaults.harvest.allowedTools }

        val failureStrategy = runCatching {
            FailureStrategy.valueOf(
                yaml.getString("replant.failure-strategy", defaults.replant.failureStrategy.name)!!
                    .uppercase(Locale.ROOT)
            )
        }.getOrElse {
            plugin.logger.log(Level.WARNING, "Invalid replant.failure-strategy, fallback to RETRY", it)
            FailureStrategy.RETRY
        }
        val plantParticle = runCatching {
            Particle.valueOf(
                yaml.getString("effects.plant-particles.particle", defaults.effects.plantParticles.particle.name)!!
                    .uppercase(Locale.ROOT)
            )
        }.getOrElse {
            plugin.logger.log(Level.WARNING, "Invalid effects.plant-particles.particle, fallback to HAPPY_VILLAGER", it)
            defaults.effects.plantParticles.particle
        }

        return AppConfig(
            language = LanguageConfig(
                default = yaml.getString("language.default", defaults.language.default)!!.lowercase(Locale.ROOT)
            ),
            messages = MessageConfig(
                prefix = yaml.getString("messages.prefix", defaults.messages.prefix)!!
            ),
            logging = LoggingConfig(
                debug = yaml.getBoolean("logging.debug", defaults.logging.debug)
            ),
            harvest = HarvestConfig(
                allowEmptyHand = yaml.getBoolean("harvest.allow-empty-hand", defaults.harvest.allowEmptyHand),
                sneakBypassReplant = yaml.getBoolean(
                    "harvest.sneak-bypass-replant",
                    defaults.harvest.sneakBypassReplant
                ),
                allowedTools = allowedTools
            ),
            protection = ProtectionConfig(
                protectSystemSaplings = yaml.getBoolean(
                    "protection.protect-system-saplings",
                    defaults.protection.protectSystemSaplings
                ),
                protectManagedTrees = yaml.getBoolean(
                    "protection.protect-managed-trees",
                    defaults.protection.protectManagedTrees
                ),
                bypassRequiresSneaking = yaml.getBoolean(
                    "protection.bypass-requires-sneaking",
                    defaults.protection.bypassRequiresSneaking
                ),
                preventFarmlandTrample = yaml.getBoolean(
                    "protection.prevent-farmland-trample",
                    defaults.protection.preventFarmlandTrample
                )
            ),
            replant = ReplantConfig(
                failureStrategy = failureStrategy,
                retryDelayTicks = yaml.getLong("replant.retry-delay-ticks", defaults.replant.retryDelayTicks)
                    .coerceAtLeast(1L),
                maxRetryAttempts = yaml.getInt("replant.max-retry-attempts", defaults.replant.maxRetryAttempts)
                    .coerceAtLeast(0),
                requiredClearHeight = yaml.getInt("replant.required-clear-height", defaults.replant.requiredClearHeight)
                    .coerceIn(0, 32),
                rootWaitRetryDelayTicks = yaml.getLong(
                    "replant.root-wait-retry-delay-ticks",
                    defaults.replant.rootWaitRetryDelayTicks
                ).coerceAtLeast(1L),
                rootWaitTimeoutSeconds = yaml.getLong(
                    "replant.root-wait-timeout-seconds",
                    defaults.replant.rootWaitTimeoutSeconds
                ).coerceAtLeast(10L),
                recoveryOnStartup = yaml.getBoolean("replant.recovery-on-startup", defaults.replant.recoveryOnStartup)
            ),
            detection = DetectionConfig(
                maxLogNodes = yaml.getInt("detection.max-log-nodes", defaults.detection.maxLogNodes).coerceIn(8, 512),
                maxLeafScanBlocks = yaml.getInt(
                    "detection.max-leaf-scan-blocks",
                    defaults.detection.maxLeafScanBlocks
                ).coerceIn(32, 4096),
                maxHorizontalDistance = yaml.getInt(
                    "detection.max-horizontal-distance",
                    defaults.detection.maxHorizontalDistance
                ).coerceIn(2, 16),
                maxTreeHeight = yaml.getInt("detection.max-tree-height", defaults.detection.maxTreeHeight)
                    .coerceIn(4, 64),
                canopyPadding = yaml.getInt("detection.canopy-padding", defaults.detection.canopyPadding)
                    .coerceIn(1, 8),
                minLeafToLogRatio = yaml.getDouble(
                    "detection.min-leaf-to-log-ratio",
                    defaults.detection.minLeafToLogRatio
                ).coerceIn(0.0, 4.0),
                minLogNodes = yaml.getInt("detection.min-log-nodes", defaults.detection.minLogNodes).coerceAtLeast(1),
                minLeafNodes = yaml.getInt("detection.min-leaf-nodes", defaults.detection.minLeafNodes).coerceAtLeast(0),
                minLeafLayers = yaml.getInt("detection.min-leaf-layers", defaults.detection.minLeafLayers)
                    .coerceIn(1, 8),
                minTrunkCoreRatio = yaml.getDouble(
                    "detection.min-trunk-core-ratio",
                    defaults.detection.minTrunkCoreRatio
                ).coerceIn(0.0, 1.0)
            ),
            effects = EffectsConfig(
                plantParticles = PlantParticleConfig(
                    enabled = yaml.getBoolean(
                        "effects.plant-particles.enabled",
                        defaults.effects.plantParticles.enabled
                    ),
                    particle = plantParticle,
                    count = yaml.getInt("effects.plant-particles.count", defaults.effects.plantParticles.count)
                        .coerceIn(0, 128),
                    offset = yaml.getDouble("effects.plant-particles.offset", defaults.effects.plantParticles.offset)
                        .coerceIn(0.0, 3.0),
                    extra = yaml.getDouble("effects.plant-particles.extra", defaults.effects.plantParticles.extra)
                        .coerceIn(0.0, 1.0)
                )
            ),
            plants = PlantsConfig(
                sugarCane = SugarCaneConfig(
                    leaveBase = yaml.getBoolean("plants.sugar-cane.leave-base", defaults.plants.sugarCane.leaveBase),
                    respectSneakBypass = yaml.getBoolean(
                        "plants.sugar-cane.respect-sneak-bypass",
                        defaults.plants.sugarCane.respectSneakBypass
                    )
                )
            ),
            persistence = PersistenceConfig(
                fileName = yaml.getString("persistence.file-name", defaults.persistence.fileName)!!,
                flushDelayMillis = yaml.getLong("persistence.flush-delay-millis", defaults.persistence.flushDelayMillis)
                    .coerceIn(0, 10000),
                terminalTaskRetentionSeconds = yaml.getLong(
                    "persistence.terminal-task-retention-seconds",
                    defaults.persistence.terminalTaskRetentionSeconds
                ).coerceAtLeast(60)
            ),
            wilderness = WildernessConfig(
                enabled = yaml.getBoolean("wilderness.enabled", defaults.wilderness.enabled),
                originWorld = WildernessOriginWorldConfig(
                    name = yaml.getString("wilderness.origin-world.name", defaults.wilderness.originWorld.name)!!,
                    autoCreate = yaml.getBoolean(
                        "wilderness.origin-world.auto-create",
                        defaults.wilderness.originWorld.autoCreate
                    ),
                    readonly = yaml.getBoolean(
                        "wilderness.origin-world.readonly",
                        defaults.wilderness.originWorld.readonly
                    )
                ),
                safety = WildernessSafetyConfig(
                    defaultMode = yaml.getString("wilderness.safety.default-mode", defaults.wilderness.safety.defaultMode)!!
                        .uppercase(Locale.ROOT),
                    denyContainerOverwrite = yaml.getBoolean(
                        "wilderness.safety.deny-container-overwrite",
                        defaults.wilderness.safety.denyContainerOverwrite
                    ),
                    denyInventoryHolderOverwrite = yaml.getBoolean(
                        "wilderness.safety.deny-inventory-holder-overwrite",
                        defaults.wilderness.safety.denyInventoryHolderOverwrite
                    ),
                    denyTileStateOverwrite = yaml.getBoolean(
                        "wilderness.safety.deny-tile-state-overwrite",
                        defaults.wilderness.safety.denyTileStateOverwrite
                    ),
                    denyEntityItemOverwrite = yaml.getBoolean(
                        "wilderness.safety.deny-entity-item-overwrite",
                        defaults.wilderness.safety.denyEntityItemOverwrite
                    ),
                    requireConfirmForMediumRisk = yaml.getBoolean(
                        "wilderness.safety.require-confirm-for-medium-risk",
                        defaults.wilderness.safety.requireConfirmForMediumRisk
                    ),
                    requireConfirmForForce = yaml.getBoolean(
                        "wilderness.safety.require-confirm-for-force",
                        defaults.wilderness.safety.requireConfirmForForce
                    ),
                    failClosedOnUnknownState = yaml.getBoolean(
                        "wilderness.safety.fail-closed-on-unknown-state",
                        defaults.wilderness.safety.failClosedOnUnknownState
                    )
                ),
                protection = WildernessProtectionConfig(
                    containerPaddingXz = yaml.getInt(
                        "wilderness.protection.container-padding-xz",
                        defaults.wilderness.protection.containerPaddingXz
                    ).coerceIn(0, 128),
                    containerPaddingY = yaml.getInt(
                        "wilderness.protection.container-padding-y",
                        defaults.wilderness.protection.containerPaddingY
                    ).coerceIn(0, 128),
                    bedPaddingXz = yaml.getInt("wilderness.protection.bed-padding-xz", defaults.wilderness.protection.bedPaddingXz)
                        .coerceIn(0, 128),
                    bedPaddingY = yaml.getInt("wilderness.protection.bed-padding-y", defaults.wilderness.protection.bedPaddingY)
                        .coerceIn(0, 128),
                    signPaddingXz = yaml.getInt(
                        "wilderness.protection.sign-padding-xz",
                        defaults.wilderness.protection.signPaddingXz
                    ).coerceIn(0, 128),
                    signPaddingY = yaml.getInt(
                        "wilderness.protection.sign-padding-y",
                        defaults.wilderness.protection.signPaddingY
                    ).coerceIn(0, 128),
                    redstonePaddingXz = yaml.getInt(
                        "wilderness.protection.redstone-padding-xz",
                        defaults.wilderness.protection.redstonePaddingXz
                    ).coerceIn(0, 128),
                    redstonePaddingY = yaml.getInt(
                        "wilderness.protection.redstone-padding-y",
                        defaults.wilderness.protection.redstonePaddingY
                    ).coerceIn(0, 128),
                    buildingPaddingXz = yaml.getInt(
                        "wilderness.protection.building-padding-xz",
                        defaults.wilderness.protection.buildingPaddingXz
                    ).coerceIn(0, 128),
                    buildingPaddingY = yaml.getInt(
                        "wilderness.protection.building-padding-y",
                        defaults.wilderness.protection.buildingPaddingY
                    ).coerceIn(0, 128),
                    manualPaddingXz = yaml.getInt(
                        "wilderness.protection.manual-padding-xz",
                        defaults.wilderness.protection.manualPaddingXz
                    ).coerceIn(0, 128),
                    manualPaddingY = yaml.getInt(
                        "wilderness.protection.manual-padding-y",
                        defaults.wilderness.protection.manualPaddingY
                    ).coerceIn(0, 128)
                ),
                scanner = WildernessScannerConfig(
                    maxChunksPerScan = yaml.getInt(
                        "wilderness.scanner.max-chunks-per-scan",
                        defaults.wilderness.scanner.maxChunksPerScan
                    ).coerceIn(1, 4096),
                    maxBlocksPerRegionTick = yaml.getInt(
                        "wilderness.scanner.max-blocks-per-region-tick",
                        defaults.wilderness.scanner.maxBlocksPerRegionTick
                    ).coerceIn(256, 65536),
                    surfaceRestoreMinOffset = yaml.getInt(
                        "wilderness.scanner.surface-restore-min-offset",
                        defaults.wilderness.scanner.surfaceRestoreMinOffset
                    ).coerceIn(-64, 0),
                    surfaceRestoreMaxOffset = yaml.getInt(
                        "wilderness.scanner.surface-restore-max-offset",
                        defaults.wilderness.scanner.surfaceRestoreMaxOffset
                    ).coerceIn(0, 128),
                    lowRiskMaxBuildScore = yaml.getInt(
                        "wilderness.scanner.low-risk-max-build-score",
                        defaults.wilderness.scanner.lowRiskMaxBuildScore
                    ).coerceAtLeast(0),
                    mediumRiskMaxBuildScore = yaml.getInt(
                        "wilderness.scanner.medium-risk-max-build-score",
                        defaults.wilderness.scanner.mediumRiskMaxBuildScore
                    ).coerceAtLeast(1),
                    highRiskMinBuildScore = yaml.getInt(
                        "wilderness.scanner.high-risk-min-build-score",
                        defaults.wilderness.scanner.highRiskMinBuildScore
                    ).coerceAtLeast(1),
                    damageThreshold = yaml.getInt(
                        "wilderness.scanner.damage-threshold",
                        defaults.wilderness.scanner.damageThreshold
                    ).coerceAtLeast(0)
                ),
                performance = WildernessPerformanceConfig(
                    maxBlocksApplyPerRegionTick = yaml.getInt(
                        "wilderness.performance.max-blocks-apply-per-region-tick",
                        defaults.wilderness.performance.maxBlocksApplyPerRegionTick
                    ).coerceIn(128, 65536),
                    maxJobsRunning = yaml.getInt(
                        "wilderness.performance.max-jobs-running",
                        defaults.wilderness.performance.maxJobsRunning
                    ).coerceIn(1, 16),
                    maxRegionJobsRunning = yaml.getInt(
                        "wilderness.performance.max-region-jobs-running",
                        defaults.wilderness.performance.maxRegionJobsRunning
                    ).coerceIn(1, 64),
                    requireTpsAbove = yaml.getDouble(
                        "wilderness.performance.require-tps-above",
                        defaults.wilderness.performance.requireTpsAbove
                    ).coerceIn(0.0, 20.0),
                    requireMsptBelow = yaml.getDouble(
                        "wilderness.performance.require-mspt-below",
                        defaults.wilderness.performance.requireMsptBelow
                    ).coerceIn(1.0, 1000.0),
                    pauseWhenPlayerNearby = yaml.getBoolean(
                        "wilderness.performance.pause-when-player-nearby",
                        defaults.wilderness.performance.pauseWhenPlayerNearby
                    ),
                    playerSafeDistance = yaml.getInt(
                        "wilderness.performance.player-safe-distance",
                        defaults.wilderness.performance.playerSafeDistance
                    ).coerceIn(0, 512)
                ),
                backup = WildernessBackupConfig(
                    compression = yaml.getString("wilderness.backup.compression", defaults.wilderness.backup.compression)!!
                        .lowercase(Locale.ROOT),
                    atomicWrite = yaml.getBoolean("wilderness.backup.atomic-write", defaults.wilderness.backup.atomicWrite),
                    keepDays = yaml.getInt("wilderness.backup.keep-days", defaults.wilderness.backup.keepDays).coerceAtLeast(1),
                    verifyHash = yaml.getBoolean("wilderness.backup.verify-hash", defaults.wilderness.backup.verifyHash)
                ),
                preview = WildernessPreviewConfig(
                    particleEnabled = yaml.getBoolean(
                        "wilderness.preview.particle-enabled",
                        defaults.wilderness.preview.particleEnabled
                    ),
                    particleDurationSeconds = yaml.getInt(
                        "wilderness.preview.particle-duration-seconds",
                        defaults.wilderness.preview.particleDurationSeconds
                    ).coerceIn(1, 300),
                    useClientBlockPreview = yaml.getBoolean(
                        "wilderness.preview.use-client-block-preview",
                        defaults.wilderness.preview.useClientBlockPreview
                    )
                )
            )
        )
    }

    fun ensureResource(resourcePath: String, target: Path) {
        if (Files.exists(target)) return
        Files.createDirectories(target.parent)
        plugin.getResource(resourcePath)?.use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("Missing bundled resource $resourcePath")
    }
}
