package code.blurone.cowardless

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.RegisteredListener
import java.io.File

class SilentPlayerJoinListener(
    private val oldPjeListeners: Array<RegisteredListener>, 
    private val withMessage: Boolean,
    private val messagesFile: File
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        event.joinMessage(if (withMessage) {
            val messages = YamlConfiguration.loadConfiguration(messagesFile)
            val locale = event.player.locale().language
            val messageKey = if (messages.contains("$locale.chat_coward")) {
                "$locale.chat_coward"
            } else {
                "en.chat_coward"
            }
            val messageTemplate = messages.getString(messageKey, "<p> is a COWARD")!!
            
            MiniMessage.miniMessage().deserialize(
                messageTemplate,
                Placeholder.component("0", Component.text(event.player.name)),
                Placeholder.unparsed("p", event.player.name)
            ).colorIfAbsent(NamedTextColor.YELLOW)
        } else null)

        val pjeHandlerList = PlayerJoinEvent.getHandlerList()
        pjeHandlerList.unregister(this)
        pjeHandlerList.registerAll(oldPjeListeners.toList())
    }
}