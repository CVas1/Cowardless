package code.blurone.cowardless

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.*
import org.bukkit.event.player.PlayerQuitEvent.QuitReason
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.*
import kotlin.collections.contains
import kotlin.run
import kotlin.text.contains

@Suppress("unused")
class CowardlessPaper : JavaPlugin(), Listener {
    private val hurtByTickstamps: MutableMap<String, Long> = mutableMapOf()
    private val shallCancelVelocityEvent: MutableSet<String> = mutableSetOf()
    private var combatTicksThreshold: Long = 0
    private var despawnTicksThreshold: Long = 0
    private var resetDespawnThreshold: Boolean = true
    private var redWarning: Boolean = false
    private var pvpOnly: Boolean = false
    private var noMobDamageCombat: Boolean = false
    private var noEnderPearlCombat: Boolean = false
    private val enderPearlCombatWorlds: MutableSet<String> = mutableSetOf()
    private var twoSided: Boolean = true
    private var actionBar: Boolean = true
    private var chatMessages: Boolean = true
    private val actionBarRunnables: MutableMap<String, BukkitRunnable> = mutableMapOf()
    private val redUnwarnScheduledTasks: MutableMap<String, ScheduledTask> = mutableMapOf()
    private val redUnwarnRunnables: MutableMap<String, BukkitRunnable> = mutableMapOf()
    private val exemptedReasons: MutableSet<QuitReason> = mutableSetOf()
    private val commandBlacklist: MutableSet<String> = mutableSetOf()
    private val isFolia: Boolean = run {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    val factory: CowardFactory = code.blurone.cowardless.v1_21_4.ServerNpc

    private fun loadConfigValues() {
        reloadConfig()
        combatTicksThreshold = config.getLong("combat_seconds_threshold", 30) * 20L
        despawnTicksThreshold = config.getLong("despawn_seconds_threshold", 30) * 20L
        resetDespawnThreshold = config.getBoolean("reset_despawn_threshold", true)
        redWarning = config.getBoolean("red_warning", false)
        pvpOnly = config.getBoolean("pvp_only", false)
        noMobDamageCombat = config.getBoolean("no_mob_damage_combat", false)
        noEnderPearlCombat = config.getBoolean("no_ender_pearl_combat", true)
        enderPearlCombatWorlds.clear()
        enderPearlCombatWorlds.addAll(
            config.getStringList("ender_pearl_combat_worlds")
                .map { it.lowercase() }
        )
        twoSided = config.getBoolean("two_sided_pvp", true)
        actionBar = config.getBoolean("action_bar", true)
        chatMessages = config.getBoolean("chat_message", true)

        exemptedReasons.clear()
        if (config.getBoolean("exempt_kicked", true))
            exemptedReasons.add(QuitReason.KICKED)
        if (config.getBoolean("exempt_timed_out", true))
            exemptedReasons.add(QuitReason.TIMED_OUT)
        if (config.getBoolean("exempt_erroneous_state", false))
            exemptedReasons.add(QuitReason.ERRONEOUS_STATE)

        commandBlacklist.clear()
        commandBlacklist.addAll(config.getStringList("command_blacklist").map { it.lowercase() })
    }

    override fun onEnable() {
        // Plugin startup logic
        saveDefaultConfig()
        loadConfigValues()

        // Register plugin events
        server.pluginManager.registerEvents(this, this)

        if (actionBar || chatMessages)
            server.asyncScheduler.runNow(this) { setupTranslations() }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("cowardless", ignoreCase = true)) {
            if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                if (!sender.hasPermission("cowardless.reload")) {
                    sender.sendMessage(Component.text("You do not have permission to use this command.", net.kyori.adventure.text.format.NamedTextColor.RED))
                    return true
                }
                loadConfigValues()
                sender.sendMessage(Component.text("Cowardless configuration reloaded!", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                return true
            }
        }
        return false
    }

    fun setupTranslations() {
        val file = File(dataFolder, "messages.yml")
        if (!file.exists()) {
            saveResource("messages.yml", false)
        }
        val messages = YamlConfiguration.loadConfiguration(file)
        val store = MiniMessageTranslationStore.create(Key.key( "cowardless:messages"))
        val entries = messages.getKeys(false)
        for (entry in entries) {
            val localeSection = messages.getConfigurationSection(entry) ?: continue
            val locales = Locale.getAvailableLocales().filter { locale ->
                val tag = locale.toLanguageTag()
                    tag == entry || tag.contains(Regex("^$entry")) && tag !in entries
            }
            for (locale in locales) {
                store.registerAll(locale, localeSection.getKeys(false)) { key ->
                    localeSection.getString(key, "")!!
                }
            }
        }

        GlobalTranslator.translator().addSource(store)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNpcDamagedByPlayer(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val damagerIsPlayer = event.damager is Player
        // Fix ServerNpc no knockback
        if (player.name in Coward.byName && damagerIsPlayer)
        {
            shallCancelVelocityEvent.add(player.name)
            return
        }

        if (damagerIsPlayer && event.damage > 0) {
            if (pvpOnly && player.name !in hurtByTickstamps) damageHandler(player, event.cause)

            if (twoSided) damageHandler(event.damager as Player, event.cause)
        }
    }

    // Fix ServerNpc no knockback
    // TODO: check if this is still a problem in versions newer than 1.21.4
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onNpcVelocityCanceler(event: PlayerVelocityEvent) {
        if (shallCancelVelocityEvent.remove(event.player.name))
            event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.damage <= 0) return

        // Reset timer for NPC
        Coward.byName[event.entity.name]?.let {
            if (resetDespawnThreshold && player.health != 0.0)
                it.remainingTicks = despawnTicksThreshold
            return
        }

        // Skip mob damage if no_mob_damage_combat is enabled
        if (event is EntityDamageByEntityEvent) {
            val damager = event.damager
            val isEnderPearl = damager is EnderPearl || damager.type == EntityType.ENDER_PEARL

            if (isEnderPearl && noEnderPearlCombat) {
                val worldName = player.world.name.lowercase()
                // Only allow ender pearl combat in specific worlds
                if (worldName !in enderPearlCombatWorlds) return
                // World is in enderPearlCombatWorlds, skip mob check and allow combat
            } else if (noMobDamageCombat) {
                // If damager is not a player (i.e., it's a mob), skip combat state
                if (damager !is Player) return
            }
        }

        if (!pvpOnly || player.name in hurtByTickstamps)
            damageHandler(player, event.cause)
    }

    fun damageHandler(player: Player, cause: DamageCause) {
        val inTicks = when (cause) {
            // Constant damage
            DamageCause.CONTACT,
            DamageCause.SUFFOCATION,
            DamageCause.FIRE,
            DamageCause.FIRE_TICK,
            DamageCause.LAVA,
            DamageCause.DROWNING,
            DamageCause.VOID,
            DamageCause.HOT_FLOOR,
            //DamageCause.CAMPFIRE,
            DamageCause.CRAMMING,
            DamageCause.FREEZE
                -> if ((hurtByTickstamps[player.name] ?: 0L) > player.world.gameTime + 50L) combatTicksThreshold else 40L

            // Pvp damage
            DamageCause.ENTITY_ATTACK,
            DamageCause.ENTITY_SWEEP_ATTACK,
            DamageCause.PROJECTILE,
            DamageCause.BLOCK_EXPLOSION,
            DamageCause.ENTITY_EXPLOSION,
            DamageCause.POISON,
            DamageCause.MAGIC,
            DamageCause.WITHER,
            DamageCause.THORNS,
            DamageCause.SONIC_BOOM
                -> combatTicksThreshold

            else -> return
        }

        setCombatTicks(player, inTicks)
    }

    fun setCombatTicks(player: Player, ticks: Long) {
        // Set timestamp for cowards
        hurtByTickstamps[player.name] = player.world.gameTime + ticks

        if (redWarning) addRedWarning(player, ticks)
        if (actionBar) {
            actionBarRunnables.remove(player.name)?.cancel()
            val runnable = ActionBarRunnable(player, ticks / 20L)
            actionBarRunnables[player.name] = runnable
            if (isFolia)
                runnable.task = player.scheduler.runAtFixedRate(this, {
                    runnable.run()
                }, null, 20L, 20L)
            else
                runnable.runTaskTimer(this, 20L, 20L)

            // We set initial delays to 20 ticks and run first time now to avoid 1 tick dephasing with red warning tasks
            runnable.run()
        }
    }

    fun addRedWarning(player: Player, ticks: Long) {
        redUnwarnScheduledTasks.remove(player.name)?.cancel()

        redUnwarnRunnables.remove(player.name)?.run()
        val oldWorldBorder = player.worldBorder ?: run {
            player.worldBorder = Bukkit.createWorldBorder()
            player.worldBorder!!
        }
        val oldWarningDistance = oldWorldBorder.warningDistance
        oldWorldBorder.warningDistance = Int.MAX_VALUE
        val runnable = object : BukkitRunnable() {
            override fun run() {
                if (oldWorldBorder == player.worldBorder)
                    oldWorldBorder.warningDistance = oldWarningDistance
            }
        }
        redUnwarnRunnables[player.name] = runnable

        player.scheduler.runDelayed(this, { runnable.run() }, runnable, ticks)?.let {
            redUnwarnScheduledTasks[player.name] = it
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDead(event: PlayerDeathEvent) {
        // Get rid of the timestamp
        hurtByTickstamps.remove(event.entity.name)
        redUnwarnScheduledTasks.remove(event.entity.name)?.cancel()
        redUnwarnRunnables.remove(event.entity.name)?.run()
        actionBarRunnables.remove(event.entity.name)?.cancel()

        // Remove the NPC if present
        Coward.byName[event.entity.name]?.let {
            // Prevent removal from timer reaching 0
            it.remainingTicks = -1L
            /*
            The good thing about using this scheduler is that there won't be a race condition if the player rejoins in
            the second where the npc is waiting for the above runnable to be called, since it won't be executed if the
            entity has been retired.
            */
            event.entity.scheduler.runDelayed(this, { _ ->
                it.remove("${it.name}'s NPCoward has died.", event.isAsynchronous)
            }, null, 20L)
        }
    }

    @EventHandler
    fun onLeave(event: PlayerQuitEvent) {
        if (
            (hurtByTickstamps.remove(event.player.name) ?: return) <= event.player.world.gameTime ||
            event.reason in exemptedReasons
        ) return

        val player = event.player

        server.globalRegionScheduler.runAtFixedRate(this, { task ->
            if (player.isOnline) return@runAtFixedRate
            if (!chatMessages)
                logger.info("${player.name} is a COWARD!")
            // Create and spawn NPC
            factory.createNpc(this@CowardlessPaper, player, despawnTicksThreshold, isFolia)
            task.cancel()
        }, 1L, 1L)
    }

    @EventHandler
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        Coward.byName[event.name]?.let {
            hurtByTickstamps[event.name] = combatTicksThreshold
            it.remove(
                "${event.name}'s NPCoward has been replaced by the real player.", true
            )
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        hurtByTickstamps[event.player.name]?.let { hurtByTickstamp ->
            // TODO: Maybe setCombatTicks even if retired in case it leaves again before this is executed??
            event.player.scheduler.run(this, {setCombatTicks(event.player, hurtByTickstamp)}, null)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val fromWorld = event.from.world
        val toWorld = event.to?.world ?: return
        
        // Only handle cross-world teleports
        if (fromWorld.uid == toWorld.uid) return
        
        val hurtByTickstamp = hurtByTickstamps[event.player.name] ?: return
        
        // Calculate remaining combat time based on the old world's game time
        val remainingTicks = hurtByTickstamp - fromWorld.gameTime
        
        // If combat should have ended, remove it
        if (remainingTicks <= 0) {
            hurtByTickstamps.remove(event.player.name)
            redUnwarnScheduledTasks.remove(event.player.name)?.cancel()
            redUnwarnRunnables.remove(event.player.name)?.run()
            actionBarRunnables.remove(event.player.name)?.cancel()
        } else {
            // Schedule the combat timer update for after teleport completes
            event.player.scheduler.run(this, {
                if (event.player.isOnline) {
                    setCombatTicks(event.player, remainingTicks)
                }
            }, null)
        }
    }

    // Use LOWEST priority to ensure we block the command before other plugins handle it
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerCommandPreprocessEvent(event: PlayerCommandPreprocessEvent) {
        val hurtByTickstamp = hurtByTickstamps[event.player.name] ?: return
        val currentGameTime = event.player.world.gameTime
        
        // Check if timestamp is way off (indicating cross-world timestamp issue)
        // If timestamp is more than 24 hours worth of ticks ahead, it's from another world
        val maxReasonableTimestamp = currentGameTime + (20L * 60 * 60 * 24) // 24 hours in ticks
        
        if (hurtByTickstamp > maxReasonableTimestamp) {
            // Timestamp is from another world with much higher gameTime
            // Clear combat state since we can't determine remaining time
            hurtByTickstamps.remove(event.player.name)
            redUnwarnScheduledTasks.remove(event.player.name)?.cancel()
            redUnwarnRunnables.remove(event.player.name)?.run()
            actionBarRunnables.remove(event.player.name)?.cancel()
            return
        }
        
        // Normal combat check
        if (hurtByTickstamp <= currentGameTime) return

        val commandPart = event.message.split(' ').first().removePrefix("/").lowercase()

        var isBlocked = commandPart in commandBlacklist
        if (!isBlocked && commandPart.contains(':')) {
            val aliasPart = commandPart.split(':').last()
            if (aliasPart in commandBlacklist) {
                isBlocked = true
            }
        }

        if (isBlocked) {
            event.isCancelled = true
            event.player.sendMessage(Component.translatable("command_blocked"))
        }
    }
}