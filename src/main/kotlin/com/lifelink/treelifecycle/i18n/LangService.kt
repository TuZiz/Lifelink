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
                "<prefix><#8BE9FD>Usage: /lifelink <#50FA7B>reload</#50FA7B>, <#50FA7B>recover</#50FA7B>, or <#50FA7B>saplingmode</#50FA7B></#8BE9FD>",
                null,
                null
            )
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
