package com.randomwars.command

import com.randomwars.RandomWarsPlugin
import com.randomwars.Text
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** /rw region ..., /rw supply ... */
class SupplyCommands(private val plugin: RandomWarsPlugin) {

    fun region(sender: CommandSender, args: List<String>) {
        val store = plugin.regions
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> {
                val player = sender as? Player ?: return
                val name = args.getOrNull(1)
                val radius = args.getOrNull(2)?.toIntOrNull() ?: 0
                if (name == null || radius < 0) {
                    sender.sendMessage(Text.prefix("<red>/rw region add <이름> [반경=0]"))
                    return
                }
                if (player.world != plugin.zones.play) {
                    sender.sendMessage(Text.prefix("<red>플레이존 월드(${plugin.zones.play.name})에서 등록하세요."))
                    return
                }
                store.add(name, player.location, radius)
                sender.sendMessage(Text.prefix("보급 지역 <gold>$name</gold> 등록 (반경 $radius, 총 ${store.regions.size}곳)"))
            }
            "list" -> {
                sender.sendMessage(Text.prefix("보급 지역 ${store.regions.size}곳"))
                store.regions.forEach { r ->
                    sender.sendMessage(Text.mm("  <gold>${r.name}</gold> <gray>${r.x.toInt()}, ${r.y?.toInt() ?: "지면"}, ${r.z.toInt()} / 반경 ${r.radius}"))
                }
            }
            "remove" -> {
                val name = args.getOrNull(1)
                if (name == null || !store.remove(name)) sender.sendMessage(Text.prefix("<red>/rw region remove <이름>"))
                else sender.sendMessage(Text.prefix("보급 지역 $name 삭제"))
            }
            "tp" -> {
                val player = sender as? Player ?: return
                val r = args.getOrNull(1)?.let { store[it] }
                if (r == null) {
                    sender.sendMessage(Text.prefix("<red>/rw region tp <이름>"))
                    return
                }
                val play = plugin.zones.play
                val y = r.y ?: (play.getHighestBlockYAt(r.x.toInt(), r.z.toInt()) + 1.0)
                player.teleportAsync(Location(play, r.x, y, r.z))
            }
            else -> sender.sendMessage(Text.prefix("<red>/rw region <add|list|remove|tp>"))
        }
    }

    fun supply(sender: CommandSender, args: List<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "now" -> {
                val names = plugin.supply.dropNow()
                sender.sendMessage(Text.prefix(if (names.isEmpty()) "<red>보급 지역이 없습니다." else "보급 투하: ${names.joinToString(", ")}"))
            }
            "status", null -> {
                val left = plugin.supply.secondsUntilNext()
                val active = plugin.supply.activeRegionNames().ifEmpty { listOf("없음") }
                sender.sendMessage(Text.prefix("다음 보급까지 ${left / 60}분 ${left % 60}초, 남은 보급: ${active.joinToString(", ")}"))
            }
            else -> sender.sendMessage(Text.prefix("<red>/rw supply <now|status>"))
        }
    }
}
