package com.lifelink.treelifecycle.i18n

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration

class MessageService(
    plugin: JavaPlugin,
    private val langService: LangService
) : AutoCloseable {
    private val audiences = BukkitAudiences.create(plugin)
    private val miniMessage = MiniMessage.miniMessage()

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        val bundle = langService.current()
        val definition = bundle.message(key)
        val resolver = resolver(bundle, placeholders)

        val audience = audiences.sender(sender)
        when (definition.type) {
            MessageTarget.CHAT -> audience.sendMessage(miniMessage.deserialize(definition.text, resolver))
            MessageTarget.ACTIONBAR -> audience.sendActionBar(miniMessage.deserialize(definition.text, resolver))
            MessageTarget.TITLE -> {
                val title = definition.title ?: definition.text
                val subtitle = definition.subtitle ?: ""
                audience.showTitle(
                    Title.title(
                        miniMessage.deserialize(title, resolver),
                        miniMessage.deserialize(subtitle, resolver),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(350))
                    )
                )
            }
        }
    }

    fun playSound(sender: CommandSender, key: String) {
        val player = sender as? Player ?: return
        val sound = langService.current().sound(key) ?: return
        if (!sound.enabled) return
        player.playSound(player.location, sound.name, sound.volume, sound.pitch)
    }

    fun treeTypeName(key: String): String = langService.current().treeTypeName(key)

    fun standardPlaceholders(player: Player?, world: String?, x: Int?, y: Int?, z: Int?): Map<String, String> =
        buildMap {
            put("player", player?.name ?: "")
            put("world", world ?: "")
            put("x", x?.toString() ?: "")
            put("y", y?.toString() ?: "")
            put("z", z?.toString() ?: "")
        }

    private fun resolver(bundle: LangBundle, placeholders: Map<String, String>): TagResolver {
        val resolvers = mutableListOf<TagResolver>(
            Placeholder.component("prefix", miniMessage.deserialize(bundle.prefix))
        )
        placeholders.forEach { (key, value) -> resolvers += Placeholder.unparsed(key, value) }
        return TagResolver.resolver(resolvers)
    }

    override fun close() {
        audiences.close()
    }
}
