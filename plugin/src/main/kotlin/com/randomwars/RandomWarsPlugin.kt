package com.randomwars

import com.randomwars.box.BoxOpener
import com.randomwars.box.LootTable
import com.randomwars.command.RwCommand
import com.randomwars.pack.ResourcePackService
import com.randomwars.weapon.WeaponRegistry
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class RandomWarsPlugin : JavaPlugin() {
    lateinit var weapons: WeaponRegistry
        private set
    lateinit var loot: LootTable
        private set
    private lateinit var boxOpener: BoxOpener
    private lateinit var resourcePack: ResourcePackService

    override fun onEnable() {
        saveDefaultConfig()
        listOf("weapons.yml", "loot.yml").forEach { if (!File(dataFolder, it).exists()) saveResource(it, false) }

        weapons = WeaponRegistry(logger)
        loot = LootTable(logger, weapons)
        boxOpener = BoxOpener(this, loot) { config.getInt("box.roll-ticks", 30) }
        resourcePack = ResourcePackService(this)
        reloadAll()

        server.pluginManager.registerEvents(boxOpener, this)
        server.pluginManager.registerEvents(resourcePack, this)
        getCommand("rw")!!.setExecutor(RwCommand(this))
    }

    override fun onDisable() {
        if (::boxOpener.isInitialized) boxOpener.shutdown()
        if (::resourcePack.isInitialized) resourcePack.stop()
    }

    fun reloadAll() {
        reloadConfig()
        weapons.load(File(dataFolder, "weapons.yml"))
        loot.load(File(dataFolder, "loot.yml"))
        resourcePack.start(config.getConfigurationSection("resource-pack"))
    }
}
