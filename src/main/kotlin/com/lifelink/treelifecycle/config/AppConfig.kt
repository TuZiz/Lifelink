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
    val persistence: PersistenceConfig
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
