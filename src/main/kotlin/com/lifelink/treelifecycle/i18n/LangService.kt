package com.lifelink.treelifecycle.i18n

import com.lifelink.treelifecycle.config.ConfigService
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class LangService(
    private val plugin: JavaPlugin,
    private val configService: ConfigService,
    private val ioExecutor: Executor
) {
    private val current = AtomicReference(defaultBundle())

    fun current(): LangBundle = current.get()

    fun loadAsync(locale: String, prefix: String): CompletableFuture<LangBundle> =
        CompletableFuture.supplyAsync({
            val normalized = locale.lowercase(Locale.ROOT)
            configService.ensureResource("lang/en_us.yml", plugin.dataFolder.toPath().resolve("lang/en_us.yml"))
            configService.ensureResource("lang/zh_cn.yml", plugin.dataFolder.toPath().resolve("lang/zh_cn.yml"))
            val file = plugin.dataFolder.resolve("lang/$normalized.yml")
            val selected = if (file.exists()) file else plugin.dataFolder.resolve("lang/en_us.yml")
            val yaml = YamlConfiguration.loadConfiguration(selected)
            val bundle = parse(selected.nameWithoutExtension, prefix, yaml)
            current.set(bundle)
            bundle
        }, ioExecutor)

    private fun parse(locale: String, prefix: String, yaml: YamlConfiguration): LangBundle {
        val messages = linkedMapOf<String, MessageDefinition>()
        val messagesSection = yaml.getConfigurationSection("messages")
        messagesSection?.getKeys(false)?.forEach { key ->
            val section = messagesSection.getConfigurationSection(key)
            if (section != null) {
                messages[key] = parseMessage(section)
            }
        }

        val treeTypes = linkedMapOf<String, String>()
        yaml.getConfigurationSection("tree-types")?.getKeys(false)?.forEach { key ->
            treeTypes[key] = yaml.getString("tree-types.$key", key)!!
        }
        val sounds = linkedMapOf<String, SoundDefinition>()
        yaml.getConfigurationSection("sounds")?.getKeys(false)?.forEach { key ->
            yaml.getConfigurationSection("sounds.$key")?.let { sounds[key] = parseSound(it) }
        }
        val languagePrefix = yaml.getString("prefix")
            ?: yaml.getString("messages.prefix")
            ?: prefix

        return defaultBundle().copy(
            locale = locale,
            prefix = languagePrefix,
            messages = defaultBundle().messages + messages,
            treeTypes = defaultBundle().treeTypes + builtInTreeTypes(locale) + treeTypes,
            sounds = defaultBundle().sounds + sounds
        )
    }

    private fun parseMessage(section: ConfigurationSection): MessageDefinition =
        MessageDefinition(
            type = MessageTarget.parse(section.getString("type")),
            text = section.getString("text", "")!!,
            title = section.getString("title"),
            subtitle = section.getString("subtitle")
        )

    private fun parseSound(section: ConfigurationSection): SoundDefinition =
        SoundDefinition(
            enabled = section.getBoolean("enabled", true),
            name = section.getString("name", "minecraft:block.note_block.pling")!!,
            volume = section.getDouble("volume", 1.0).toFloat().coerceIn(0.0f, 10.0f),
            pitch = section.getDouble("pitch", 1.0).toFloat().coerceIn(0.0f, 2.0f)
        )

    private fun builtInTreeTypes(locale: String): Map<String, String> =
        if (locale.lowercase(Locale.ROOT).startsWith("zh")) {
            mapOf("pale_oak" to "苍白橡木")
        } else {
            mapOf("pale_oak" to "Pale Oak")
        }

    private fun chat(text: String): MessageDefinition = MessageDefinition(MessageTarget.CHAT, text, null, null)

    private fun defaultBundle(): LangBundle = LangBundle(
        locale = "en_us",
        prefix = "<#32C5FF>LifeLink</#32C5FF> <#7CFFB2>|</#7CFFB2> ",
        messages = mapOf(
            "plugin-loading" to MessageDefinition(
                MessageTarget.ACTIONBAR,
                "<prefix><#FFB86C>The plugin is still loading async state, please retry in a moment.</#FFB86C>",
                null,
                null
            ),
            "no-permission" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#FF5555>You do not have permission to do that.</#FF5555>",
                null,
                null
            ),
            "protected-sapling" to MessageDefinition(
                MessageTarget.ACTIONBAR,
                "<prefix><#FF5555>System replanted saplings are protected.</#FF5555>",
                null,
                null
            ),
            "managed-tree-illegal-break" to MessageDefinition(
                MessageTarget.ACTIONBAR,
                "<prefix><#FFD166>Managed trees must be harvested from the root with an axe.</#FFD166>",
                null,
                null
            ),
            "harvest-busy" to MessageDefinition(
                MessageTarget.ACTIONBAR,
                "<prefix><#FF9F1C>This location already has an active lifecycle task.</#FF9F1C>",
                null,
                null
            ),
            "harvest-replant-success" to MessageDefinition(
                MessageTarget.ACTIONBAR,
                "<prefix><#50FA7B><tree_type> has been replanted at the original location.</#50FA7B>",
                null,
                null
            ),
            "replant-retry-scheduled" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#FFD166>Replanting is deferred and will be retried automatically. Reason: <reason></#FFD166>",
                null,
                null
            ),
            "replant-failed" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#FF5555>Automatic replant failed. Reason: <reason></#FF5555>",
                null,
                null
            ),
            "reload-started" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#8BE9FD>Reloading configuration and language files asynchronously.</#8BE9FD>",
                null,
                null
            ),
            "reload-success" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#50FA7B>Reload completed.</#50FA7B>",
                null,
                null
            ),
            "reload-failed" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#FF5555>Reload failed: <reason></#FF5555>",
                null,
                null
            ),
            "recovery-queued" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#8BE9FD>Queued <reason> recovery checks.</#8BE9FD>",
                null,
                null
            ),
            "player-only-command" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#FF5555>This command can only be used by an online player.</#FF5555>",
                null,
                null
            ),
            "sapling-mode-enabled" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#50FA7B>Admin sapling mode enabled. Saplings you place will be managed for automatic replanting.</#50FA7B>",
                null,
                null
            ),
            "sapling-mode-disabled" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#A9AEB8>Admin sapling mode disabled.</#A9AEB8>",
                null,
                null
            ),
            "sapling-mode-recorded" to MessageDefinition(
                MessageTarget.ACTIONBAR,
                "<prefix><#50FA7B>Marked this <tree_type> sapling for automatic replanting.</#50FA7B>",
                null,
                null
            ),
            "sapling-mode-usage" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#8BE9FD>Usage: /lifelink saplingmode <#50FA7B>on</#50FA7B>|<#FF5555>off</#FF5555>|<#F1FA8C>status</#F1FA8C></#8BE9FD>",
                null,
                null
            ),
            "command-usage" to MessageDefinition(
                MessageTarget.CHAT,
                "<prefix><#38bdf8>输入 <#ffffff>/lifelink help</#ffffff> 查看命令说明。</#38bdf8>",
                null,
                null
            ),
            "help-title" to chat("<prefix><gradient:#22c55e:#38bdf8><bold>LifeLink 帮助</bold></gradient> <#a9aeb8>树木托管 + 保守型荒野修复</#a9aeb8>"),
            "help-tree" to chat("<#22c55e>/lifelink recover</#22c55e> <#a9aeb8>- 重扫未完成的树木补种任务，不会乱改荒野。</#a9aeb8>"),
            "help-saplingmode" to chat("<#22c55e>/lifelink saplingmode on/off/status</#22c55e> <#a9aeb8>- 管理员放树苗时纳入系统托管。</#a9aeb8>"),
            "help-wilderness" to chat("<#38bdf8>/lifelink wilderness</#38bdf8> <#a9aeb8>- 老服荒野修复入口：先扫描，后预览，再恢复。</#a9aeb8>"),
            "help-wilderness-flow" to chat("<#facc15>推荐流程</#facc15><#a9aeb8>：scan 检查风险 -> preview 看范围 -> protect 保护建筑 -> restore 修复低风险。</#a9aeb8>"),
            "help-admin" to chat("<#a78bfa>/lifelink reload</#a78bfa> <#a9aeb8>- 异步重载配置和语言。</#a9aeb8>"),
            "help-next" to chat("<#ffffff>更多说明</#ffffff><#a9aeb8>：/lifelink wilderness help，或 /lifelink wilderness help restore。</#a9aeb8>"),
            "wilderness-command-usage" to chat("<prefix><#38bdf8>输入 <#ffffff>/lifelink wilderness help</#ffffff> 查看荒野修复说明。</#38bdf8>"),
            "wilderness-help-title" to chat("<prefix><gradient:#22c55e:#38bdf8><bold>荒野修复帮助</bold></gradient> <#a9aeb8>默认保守，不确定就跳过。</#a9aeb8>"),
            "wilderness-help-purpose" to chat("<#38bdf8>用途</#38bdf8><#a9aeb8>：修爆炸坑、乱挖地表、水/岩浆污染、浮空残留；不用于覆盖玩家建筑。</#a9aeb8>"),
            "wilderness-help-flow" to chat("<#facc15>流程</#facc15><#a9aeb8>：scan 只扫描 -> preview 粒子预览 -> restore 低风险恢复 -> rollback 从快照回滚。</#a9aeb8>"),
            "wilderness-help-scan" to chat("<#22c55e>scan</#22c55e><#a9aeb8>：只检查不改方块。chunk=当前区块，radius=半径范围，selection=pos1/pos2 选区。</#a9aeb8>"),
            "wilderness-help-preview" to chat("<#38bdf8>preview</#38bdf8><#a9aeb8>：只显示粒子，绿色可恢复，黄色需确认，红色保护/高风险。</#a9aeb8>"),
            "wilderness-help-restore" to chat("<#ef4444>restore</#ef4444><#a9aeb8>：从 world_origin 复制自然方块。LOW 自动执行，MEDIUM 需要 confirm，HIGH 拒绝。</#a9aeb8>"),
            "wilderness-help-protect" to chat("<#facc15>protect</#facc15><#a9aeb8>：手动保护玩家建筑。chunk/radius 立即保护，pos1/pos2/create 创建选区保护。</#a9aeb8>"),
            "wilderness-help-selection" to chat("<#a78bfa>选区</#a78bfa><#a9aeb8>：先站到一角 /lifelink wilderness pos1，再站另一角 /lifelink wilderness pos2。</#a9aeb8>"),
            "wilderness-help-area" to chat("<#38bdf8>范围</#38bdf8><#a9aeb8>：chunk 当前区块；radius [半径] 周围区域；selection 使用 pos1/pos2。</#a9aeb8>"),
            "wilderness-help-risk" to chat("<#facc15>风险</#facc15><#a9aeb8>：箱子/床/红石/TileState=高风险；告示牌/建筑分/资产索引=中风险。</#a9aeb8>"),
            "wilderness-help-safety" to chat("<#ef4444>安全</#ef4444><#a9aeb8>：恢复前写快照；执行时再次跳过容器、红石、TileState、保护遮罩。</#a9aeb8>"),
            "wilderness-help-rollback" to chat("<#22c55e>rollback [jobId]</#22c55e><#a9aeb8>：按恢复前快照回滚，jobId 可用 /lifelink wilderness jobs 查看。</#a9aeb8>"),
            "wilderness-help-jobs" to chat("<#38bdf8>jobs/job</#38bdf8><#a9aeb8>：jobs 列出最近任务；job [id] 查看任务状态、恢复数量和跳过数量。</#a9aeb8>"),
            "wilderness-help-examples" to chat("<#ffffff>示例</#ffffff><#a9aeb8>：/lifelink wilderness scan chunk；preview radius 32；restore surface 32；protect chunk。</#a9aeb8>")
        ),
        treeTypes = mapOf(
            "oak" to "Oak",
            "birch" to "Birch",
            "spruce" to "Spruce",
            "jungle" to "Jungle",
            "acacia" to "Acacia",
            "dark_oak" to "Dark Oak",
            "mangrove" to "Mangrove",
            "cherry" to "Cherry",
            "pale_oak" to "Pale Oak"
        ),
        sounds = mapOf(
            "success" to SoundDefinition(true, "minecraft:block.note_block.pling", 1.0f, 1.2f),
            "error" to SoundDefinition(true, "minecraft:block.note_block.bass", 1.0f, 0.7f),
            "warning" to SoundDefinition(true, "minecraft:block.note_block.bit", 1.0f, 0.9f),
            "replant" to SoundDefinition(true, "minecraft:item.crop.plant", 0.8f, 1.0f),
            "wilderness-preview" to SoundDefinition(true, "minecraft:block.amethyst_block.chime", 0.8f, 1.4f),
            "wilderness-restore" to SoundDefinition(true, "minecraft:block.grass.place", 0.8f, 1.1f)
        )
    )
}
