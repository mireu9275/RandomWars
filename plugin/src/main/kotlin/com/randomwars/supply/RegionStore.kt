package com.randomwars.supply

import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 보급 지역. radius 가 0 이면 (x, y, z) 정확한 위치에, 아니면 반경 안의 지면 중 무작위 위치에 보급 상자를 둔다.
 */
data class Region(val name: String, val x: Double, val y: Double?, val z: Double, val radius: Int)

class RegionStore(private val file: File) {
    var regions: List<Region> = emptyList()
        private set

    fun load() {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("regions")
        regions = section?.getKeys(false).orEmpty().mapNotNull { name ->
            val s = section!!.getConfigurationSection(name) ?: return@mapNotNull null
            Region(
                name = name,
                x = s.getDouble("x"),
                y = if (s.contains("y")) s.getDouble("y") else null,
                z = s.getDouble("z"),
                radius = s.getInt("radius", 0),
            )
        }
    }

    fun add(name: String, loc: Location, radius: Int) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val path = "regions.$name"
        yaml.set("$path.x", loc.blockX + 0.5)
        yaml.set("$path.y", loc.blockY.toDouble())
        yaml.set("$path.z", loc.blockZ + 0.5)
        yaml.set("$path.radius", radius)
        yaml.save(file)
        load()
    }

    fun remove(name: String): Boolean {
        val yaml = YamlConfiguration.loadConfiguration(file)
        if (!yaml.contains("regions.$name")) return false
        yaml.set("regions.$name", null)
        yaml.save(file)
        load()
        return true
    }

    operator fun get(name: String) = regions.firstOrNull { it.name == name }
}
