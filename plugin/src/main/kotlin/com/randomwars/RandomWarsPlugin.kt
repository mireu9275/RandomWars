package com.randomwars

import com.randomwars.box.BoxOpener
import com.randomwars.box.LootTable
import com.randomwars.combat.CombatTracker
import com.randomwars.combat.DeathRules
import com.randomwars.command.RwCommand
import com.randomwars.command.ZoneCommands
import com.randomwars.pack.ResourcePackService
import com.randomwars.skill.Parts
import com.randomwars.skill.ProjectileTracker
import com.randomwars.skill.SkillEffects
import com.randomwars.skill.SkillService
import com.randomwars.supply.RegionStore
import com.randomwars.supply.SupplyService
import com.randomwars.weapon.WeaponRegistry
import com.randomwars.zone.ZoneConfig
import com.randomwars.zone.ZoneListener
import com.randomwars.zone.ZoneService
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class RandomWarsPlugin : JavaPlugin() {
    lateinit var weapons: WeaponRegistry
        private set
    lateinit var loot: LootTable
        private set
    lateinit var zoneConfig: ZoneConfig
        private set
    lateinit var zones: ZoneService
        private set
    lateinit var regions: RegionStore
        private set
    lateinit var supply: SupplyService
        private set
    private lateinit var boxOpener: BoxOpener
    private lateinit var resourcePack: ResourcePackService

    override fun onEnable() {
        saveDefaultConfig()
        listOf("weapons.yml", "loot.yml", "zone.yml", "regions.yml").forEach { if (!File(dataFolder, it).exists()) saveResource(it, false) }

        Parts.plugin = this
        weapons = WeaponRegistry(logger)
        loot = LootTable(logger, weapons)
        zoneConfig = ZoneConfig(File(dataFolder, "zone.yml"))
        zones = ZoneService(this, zoneConfig, loot)
        regions = RegionStore(File(dataFolder, "regions.yml"))
        boxOpener = BoxOpener(this, loot) { config.getInt("box.roll-ticks", 30) }
        resourcePack = ResourcePackService(this)
        reloadAll()

        val combat = CombatTracker(this, zones) { config.getInt("combat.tag-seconds", 15) }
        supply = SupplyService(this, zones, loot, regions,
            intervalSeconds = { config.getInt("supply.interval-seconds", 600) },
            dropCount = { config.getInt("supply.count", 2) })
        val effects = SkillEffects(this)
        val projectiles = ProjectileTracker(this, effects)
        val skills = SkillService(logger, { config.getBoolean("debug", false) }, weapons, effects, projectiles)
        listOf(boxOpener, resourcePack, ZoneListener(zones) { zoneConfig.mobSpawning }, combat, DeathRules(zoneConfig, zones, loot), supply,
            effects, projectiles, skills)
            .forEach { server.pluginManager.registerEvents(it, this) }
        combat.start()
        effects.start()
        supply.start()
        getCommand("rw")!!.setExecutor(RwCommand(this))
        val zoneCommands = ZoneCommands(zones, combat)
        getCommand("입장")!!.setExecutor(zoneCommands)
        getCommand("로비")!!.setExecutor(zoneCommands)

        // /reload 등으로 이미 접속해 있는 플레이어도 로비 규칙을 따르게 한다
        server.onlinePlayers.filterNot { zones.isInPlay(it) }.forEach { zones.sendToLobby(it) }
    }

    override fun onDisable() {
        if (::supply.isInitialized) supply.stop()
        if (::boxOpener.isInitialized) boxOpener.shutdown()
        if (::resourcePack.isInitialized) resourcePack.stop()
    }

    fun reloadAll() {
        reloadConfig()
        weapons.load(File(dataFolder, "weapons.yml"))
        loot.load(File(dataFolder, "loot.yml"))
        zoneConfig.load()
        zones.setupWorlds()
        regions.load()
        resourcePack.start(config.getConfigurationSection("resource-pack"))
    }
}
