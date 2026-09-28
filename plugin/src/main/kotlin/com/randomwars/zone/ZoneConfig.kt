package com.randomwars.zone

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale

enum class WarpMode { RANDOM, POINTS }

/** zone.yml. 후보 지점은 "x y z yaw pitch" 문자열로 저장한다. */
class ZoneConfig(private val file: File) {
    private var yaml = YamlConfiguration()

    val lobbyWorld get() = yaml.getString("lobby.world", "rw_lobby")!!
    val lobbySpawn: List<Double>? get() = parse(yaml.getString("lobby.spawn").orEmpty())
    val playWorld get() = yaml.getString("play.world", "rw_play")!!
    val center: Pair<Double, Double>
        get() = yaml.getDoubleList("play.center").let { (it.getOrNull(0) ?: 0.0) to (it.getOrNull(1) ?: 0.0) }
    val size get() = yaml.getDouble("play.size", 300.0)
    val warpMode get() = runCatching { WarpMode.valueOf(yaml.getString("play.warp-mode", "random")!!.uppercase()) }.getOrDefault(WarpMode.RANDOM)
    /** 랜덤 워프 착지 높이 상한. 0 이면 제한 없음 (건물 지붕에 떨어지지 않게) */
    val maxWarpY get() = yaml.getInt("play.max-warp-y", 0)
    val minPlayerDistance get() = yaml.getDouble("play.min-player-distance", 30.0)
    val invulnerableSeconds get() = yaml.getInt("play.invulnerable-seconds", 3)
    val entryBoxes get() = yaml.getInt("play.entry-boxes", 2)
    val killRewardBoxes get() = yaml.getInt("kill-reward.boxes", 1)
    val assistSeconds get() = yaml.getInt("kill-reward.assist-seconds", 10)
    val sameVictimCooldownSeconds get() = yaml.getInt("kill-reward.same-victim-cooldown-seconds", 300)
    val lobbyWaitSeconds get() = yaml.getInt("lobby-command.wait-seconds", 5)

    fun load() {
        yaml = YamlConfiguration.loadConfiguration(file)
    }

    fun spawnPoints(world: World): List<Location> = yaml.getStringList("spawn-points").mapNotNull { line ->
        parse(line)?.takeIf { it.size >= 3 }?.let {
            Location(world, it[0], it[1], it[2], it.getOrElse(3) { 0.0 }.toFloat(), it.getOrElse(4) { 0.0 }.toFloat())
        }
    }

    fun addSpawnPoint(loc: Location) {
        val line = String.format(Locale.ROOT, "%.2f %.2f %.2f %.1f %.1f", loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        yaml.set("spawn-points", yaml.getStringList("spawn-points") + line)
        yaml.save(file)
    }

    fun removeSpawnPoint(index: Int): Boolean {
        val list = yaml.getStringList("spawn-points")
        if (index !in list.indices) return false
        yaml.set("spawn-points", list.filterIndexed { i, _ -> i != index })
        yaml.save(file)
        return true
    }

    private fun parse(line: String): List<Double>? =
        line.trim().split(Regex("[ ,]+")).mapNotNull { it.toDoubleOrNull() }.takeIf { it.size >= 3 }
}
