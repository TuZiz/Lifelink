package com.lifelink.treelifecycle.config

import com.lifelink.treelifecycle.domain.FailureStrategy
import org.bukkit.Material
import org.bukkit.Particle

data class AppConfig(
    val language: LanguageConfig,
    val messages: MessageConfig,
    val logging: LoggingConfig,
    val harvest: HarvestConfig,
    val protection: ProtectionConfig,
    val replant: ReplantConfig,
    val detection: DetectionConfig,
    val effects: EffectsConfig,
    val plants: PlantsConfig,
    val persistence: PersistenceConfig,
    val wilderness: WildernessConfig
) {
    companion object {
        fun defaults(): AppConfig = AppConfig(
            language = LanguageConfig(default = "zh_cn"),
            messages = MessageConfig(prefix = "<#32C5FF>LifeLink</#32C5FF> <#7CFFB2>|</#7CFFB2> "),
            logging = LoggingConfig(debug = false),
            harvest = HarvestConfig(
                allowEmptyHand = true,
                sneakBypassReplant = true,
                allowedTools = setOf(
                    Material.WOODEN_AXE,
                    Material.STONE_AXE,
                    Material.IRON_AXE,
                    Material.GOLDEN_AXE,
                    Material.DIAMOND_AXE,
                    Material.NETHERITE_AXE
                )
            ),
            protection = ProtectionConfig(
                protectSystemSaplings = true,
                protectManagedTrees = true,
                bypassRequiresSneaking = true,
                preventFarmlandTrample = true
            ),
            replant = ReplantConfig(
                failureStrategy = FailureStrategy.RETRY,
                retryDelayTicks = 40L,
                maxRetryAttempts = 6,
                requiredClearHeight = 0,
                rootWaitRetryDelayTicks = 10L,
                rootWaitTimeoutSeconds = 1800L,
                recoveryOnStartup = true
            ),
            detection = DetectionConfig(
                maxLogNodes = 96,
                maxLeafScanBlocks = 512,
                maxHorizontalDistance = 6,
                maxTreeHeight = 24,
                canopyPadding = 3,
                minLeafToLogRatio = 0.85,
                minLogNodes = 3,
                minLeafNodes = 5,
                minLeafLayers = 2,
                minTrunkCoreRatio = 0.45
            ),
            effects = EffectsConfig(
                plantParticles = PlantParticleConfig(
                    enabled = true,
                    particle = Particle.HAPPY_VILLAGER,
                    count = 12,
                    offset = 0.35,
                    extra = 0.02
                )
            ),
            plants = PlantsConfig(
                sugarCane = SugarCaneConfig(
                    leaveBase = true,
                    respectSneakBypass = true
                ),
            ),
            persistence = PersistenceConfig(
                fileName = "data/state.properties",
                flushDelayMillis = 250,
                terminalTaskRetentionSeconds = 86400
            ),
            wilderness = WildernessConfig(
                enabled = true,
                originWorld = WildernessOriginWorldConfig(
                    name = "world_origin",
                    autoCreate = false,
                    readonly = true
                ),
                safety = WildernessSafetyConfig(
                    defaultMode = "SAFE",
                    denyContainerOverwrite = true,
                    denyInventoryHolderOverwrite = true,
                    denyTileStateOverwrite = true,
                    denyEntityItemOverwrite = true,
                    requireConfirmForMediumRisk = true,
                    requireConfirmForForce = true,
                    failClosedOnUnknownState = true
                ),
                protection = WildernessProtectionConfig(
                    containerPaddingXz = 16,
                    containerPaddingY = 32,
                    bedPaddingXz = 24,
                    bedPaddingY = 32,
                    signPaddingXz = 16,
                    signPaddingY = 24,
                    redstonePaddingXz = 24,
                    redstonePaddingY = 32,
                    buildingPaddingXz = 12,
                    buildingPaddingY = 24,
                    manualPaddingXz = 0,
                    manualPaddingY = 0
                ),
                scanner = WildernessScannerConfig(
                    maxChunksPerScan = 256,
                    maxBlocksPerRegionTick = 4096,
                    surfaceRestoreMinOffset = -8,
                    surfaceRestoreMaxOffset = 24,
                    lowRiskMaxBuildScore = 30,
                    mediumRiskMaxBuildScore = 120,
                    highRiskMinBuildScore = 121,
                    damageThreshold = 1000
                ),
                performance = WildernessPerformanceConfig(
                    maxBlocksApplyPerRegionTick = 2048,
                    maxJobsRunning = 1,
                    maxRegionJobsRunning = 4,
                    requireTpsAbove = 18.5,
                    requireMsptBelow = 45.0,
                    pauseWhenPlayerNearby = true,
                    playerSafeDistance = 96
                ),
                backup = WildernessBackupConfig(
                    compression = "gzip",
                    atomicWrite = true,
                    keepDays = 30,
                    verifyHash = true
                ),
                preview = WildernessPreviewConfig(
                    particleEnabled = true,
                    particleDurationSeconds = 30,
                    useClientBlockPreview = false
                )
            )
        )
    }
}

data class LanguageConfig(val default: String)

data class MessageConfig(val prefix: String)

data class LoggingConfig(val debug: Boolean)

data class HarvestConfig(
    val allowEmptyHand: Boolean,
    val sneakBypassReplant: Boolean,
    val allowedTools: Set<Material>
)

data class ProtectionConfig(
    val protectSystemSaplings: Boolean,
    val protectManagedTrees: Boolean,
    val bypassRequiresSneaking: Boolean,
    val preventFarmlandTrample: Boolean
)

data class ReplantConfig(
    val failureStrategy: FailureStrategy,
    val retryDelayTicks: Long,
    val maxRetryAttempts: Int,
    val requiredClearHeight: Int,
    val rootWaitRetryDelayTicks: Long,
    val rootWaitTimeoutSeconds: Long,
    val recoveryOnStartup: Boolean
)

data class DetectionConfig(
    val maxLogNodes: Int,
    val maxLeafScanBlocks: Int,
    val maxHorizontalDistance: Int,
    val maxTreeHeight: Int,
    val canopyPadding: Int,
    val minLeafToLogRatio: Double,
    val minLogNodes: Int,
    val minLeafNodes: Int,
    val minLeafLayers: Int,
    val minTrunkCoreRatio: Double
)

data class EffectsConfig(
    val plantParticles: PlantParticleConfig
)

data class PlantParticleConfig(
    val enabled: Boolean,
    val particle: Particle,
    val count: Int,
    val offset: Double,
    val extra: Double
)

data class PlantsConfig(
    val sugarCane: SugarCaneConfig
)

data class SugarCaneConfig(
    val leaveBase: Boolean,
    val respectSneakBypass: Boolean
)

data class PersistenceConfig(
    val fileName: String,
    val flushDelayMillis: Long,
    val terminalTaskRetentionSeconds: Long
)

data class WildernessConfig(
    val enabled: Boolean,
    val originWorld: WildernessOriginWorldConfig,
    val safety: WildernessSafetyConfig,
    val protection: WildernessProtectionConfig,
    val scanner: WildernessScannerConfig,
    val performance: WildernessPerformanceConfig,
    val backup: WildernessBackupConfig,
    val preview: WildernessPreviewConfig
)

data class WildernessOriginWorldConfig(
    val name: String,
    val autoCreate: Boolean,
    val readonly: Boolean
)

data class WildernessSafetyConfig(
    val defaultMode: String,
    val denyContainerOverwrite: Boolean,
    val denyInventoryHolderOverwrite: Boolean,
    val denyTileStateOverwrite: Boolean,
    val denyEntityItemOverwrite: Boolean,
    val requireConfirmForMediumRisk: Boolean,
    val requireConfirmForForce: Boolean,
    val failClosedOnUnknownState: Boolean
)

data class WildernessProtectionConfig(
    val containerPaddingXz: Int,
    val containerPaddingY: Int,
    val bedPaddingXz: Int,
    val bedPaddingY: Int,
    val signPaddingXz: Int,
    val signPaddingY: Int,
    val redstonePaddingXz: Int,
    val redstonePaddingY: Int,
    val buildingPaddingXz: Int,
    val buildingPaddingY: Int,
    val manualPaddingXz: Int,
    val manualPaddingY: Int
)

data class WildernessScannerConfig(
    val maxChunksPerScan: Int,
    val maxBlocksPerRegionTick: Int,
    val surfaceRestoreMinOffset: Int,
    val surfaceRestoreMaxOffset: Int,
    val lowRiskMaxBuildScore: Int,
    val mediumRiskMaxBuildScore: Int,
    val highRiskMinBuildScore: Int,
    val damageThreshold: Int
)

data class WildernessPerformanceConfig(
    val maxBlocksApplyPerRegionTick: Int,
    val maxJobsRunning: Int,
    val maxRegionJobsRunning: Int,
    val requireTpsAbove: Double,
    val requireMsptBelow: Double,
    val pauseWhenPlayerNearby: Boolean,
    val playerSafeDistance: Int
)

data class WildernessBackupConfig(
    val compression: String,
    val atomicWrite: Boolean,
    val keepDays: Int,
    val verifyHash: Boolean
)

data class WildernessPreviewConfig(
    val particleEnabled: Boolean,
    val particleDurationSeconds: Int,
    val useClientBlockPreview: Boolean
)
