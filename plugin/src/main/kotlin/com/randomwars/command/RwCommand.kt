package com.randomwars.command

import com.randomwars.RandomWarsPlugin
import com.randomwars.Text
import com.randomwars.box.BoxType
import com.randomwars.box.LootResult
import com.randomwars.weapon.Grade
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class RwCommand(private val plugin: RandomWarsPlugin) : TabExecutor {
    private val supplyCommands = SupplyCommands(plugin)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            "version", null -> sender.sendMessage(Text.prefix("RandomWars <white>${plugin.pluginMeta.version}</white> (Paper ${Bukkit.getMinecraftVersion()})"))
            "reload" -> {
                if (!admin(sender)) return true
                plugin.reloadAll()
                sender.sendMessage(Text.prefix("<green>설정을 다시 불러왔습니다. (무기 ${plugin.weapons.all.size}종)"))
            }
            "box" -> if (admin(sender)) box(sender, args.drop(1))
            "weapon" -> if (admin(sender)) weapon(sender, args.drop(1))
            "spawn" -> if (admin(sender)) spawn(sender, args.drop(1))
            "zone" -> if (admin(sender)) zone(sender)
            "region" -> if (admin(sender)) supplyCommands.region(sender, args.drop(1))
            "supply" -> if (admin(sender)) supplyCommands.supply(sender, args.drop(1))
            else -> usage(sender)
        }
        return true
    }

    private fun box(sender: CommandSender, args: List<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "give" -> {
                val target = args.getOrNull(1)?.let { Bukkit.getPlayerExact(it) }
                val type = args.getOrNull(2)?.let { BoxType.parse(it) }
                val amount = args.getOrNull(3)?.toIntOrNull() ?: 1
                if (target == null || type == null || amount !in 1..64) {
                    sender.sendMessage(Text.prefix("<red>/rw box give <플레이어> <일반|보급> [1~64]"))
                    return
                }
                target.inventory.addItem(plugin.loot.createBox(type, amount)).values
                    .forEach { target.world.dropItemNaturally(target.location, it) }
                sender.sendMessage(Text.prefix("${target.name}에게 ${type.koreanName} 랜덤상자 ${amount}개 지급"))
            }
            "sim" -> {
                val type = args.getOrNull(1)?.let { BoxType.parse(it) }
                val count = args.getOrNull(2)?.toIntOrNull() ?: 1000
                if (type == null || count !in 1..1_000_000) {
                    sender.sendMessage(Text.prefix("<red>/rw box sim <일반|보급> [횟수=1000]"))
                    return
                }
                simulate(sender, type, count)
            }
            else -> sender.sendMessage(Text.prefix("<red>/rw box <give|sim>"))
        }
    }

    /** 상자를 count 번 굴려 기대 확률과 비교한다. */
    private fun simulate(sender: CommandSender, type: BoxType, count: Int) {
        val settings = plugin.loot.settings(type)
        val weights = plugin.loot.effectiveGradeWeights(type)
        val weightSum = weights.values.sum()
        val weaponShare = 1.0 - settings.consumableChance

        val counts = mutableMapOf<Grade?, Int>()
        repeat(count) {
            val grade = when (val r = plugin.loot.roll(type)) {
                is LootResult.Weapon -> r.weapon.grade
                is LootResult.Item -> null
            }
            counts.merge(grade, 1, Int::plus)
        }

        sender.sendMessage(Text.prefix("<yellow>${type.koreanName} 랜덤상자 ${count}회 시뮬레이션"))
        var maxDiff = 0.0
        fun line(label: String, n: Int, expected: Double) {
            val actual = n * 100.0 / count
            val diff = actual - expected * 100
            maxDiff = maxOf(maxDiff, kotlin.math.abs(diff))
            val color = if (kotlin.math.abs(diff) <= 2.0) "green" else "red"
            sender.sendMessage(Text.mm("  $label<gray>: <white>$n</white>회 <white>${"%.1f".format(actual)}%</white> (기대 ${"%.1f".format(expected * 100)}%, <$color>${"%+.1f".format(diff)}</$color>)"))
        }
        for (grade in Grade.entries) {
            val expected = if (weightSum > 0) weaponShare * (weights[grade] ?: 0.0) / weightSum else 0.0
            line(grade.colored, counts[grade] ?: 0, expected)
        }
        line("<green>소모품", counts[null] ?: 0, if (weightSum > 0) settings.consumableChance else 1.0)
        val ok = maxDiff <= 2.0
        sender.sendMessage(Text.mm("  최대 오차 <white>${"%.2f".format(maxDiff)}%p</white> → " + if (ok) "<green>통과 (±2% 이내)" else "<red>초과"))
    }

    private fun weapon(sender: CommandSender, args: List<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "list" -> {
                for (grade in Grade.entries) {
                    val names = plugin.weapons.ofGrade(grade).joinToString(", ") { "${it.name}<dark_gray>(${it.id})</dark_gray>" }
                    sender.sendMessage(Text.mm("${grade.colored}<gray>: <white>$names"))
                }
            }
            "give" -> {
                val target = args.getOrNull(1)?.let { Bukkit.getPlayerExact(it) }
                val def = args.getOrNull(2)?.let { plugin.weapons[it] }
                if (target == null || def == null) {
                    sender.sendMessage(Text.prefix("<red>/rw weapon give <플레이어> <무기 id>"))
                    return
                }
                target.inventory.addItem(def.createItem())
                sender.sendMessage(Text.prefix("${target.name}에게 ${def.grade.color}${def.name}<reset> 지급"))
            }
            else -> sender.sendMessage(Text.prefix("<red>/rw weapon <list|give>"))
        }
    }

    /** 플레이존 워프 후보 지점 관리 (건물 맵용 points 모드) */
    private fun spawn(sender: CommandSender, args: List<String>) {
        val zones = plugin.zones
        val points = plugin.zoneConfig.spawnPoints(zones.play)
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> {
                val player = sender as? Player ?: return
                if (player.world != zones.play) {
                    sender.sendMessage(Text.prefix("<red>플레이존 월드(${zones.play.name})에서 등록하세요."))
                    return
                }
                plugin.zoneConfig.addSpawnPoint(player.location)
                sender.sendMessage(Text.prefix("후보 지점 #${points.size} 등록 (총 ${points.size + 1}개)"))
            }
            "list" -> {
                sender.sendMessage(Text.prefix("후보 지점 ${points.size}개"))
                points.forEachIndexed { i, p -> sender.sendMessage(Text.mm("  <gray>#$i</gray> ${p.blockX} ${p.blockY} ${p.blockZ}")) }
            }
            "remove" -> {
                val index = args.getOrNull(1)?.toIntOrNull()
                if (index == null || !plugin.zoneConfig.removeSpawnPoint(index)) {
                    sender.sendMessage(Text.prefix("<red>/rw spawn remove <번호> (번호는 /rw spawn list)"))
                    return
                }
                sender.sendMessage(Text.prefix("후보 지점 #$index 삭제"))
            }
            "tp" -> {
                val player = sender as? Player ?: return
                val p = args.getOrNull(1)?.toIntOrNull()?.let { points.getOrNull(it) }
                if (p == null) sender.sendMessage(Text.prefix("<red>/rw spawn tp <번호>")) else player.teleportAsync(p)
            }
            else -> sender.sendMessage(Text.prefix("<red>/rw spawn <add|list|remove|tp>"))
        }
    }

    private fun zone(sender: CommandSender) {
        val c = plugin.zoneConfig
        val border = plugin.zones.play.worldBorder
        sender.sendMessage(Text.prefix("로비 <white>${plugin.zones.lobby.name}</white>, 플레이존 <white>${plugin.zones.play.name}</white>"))
        sender.sendMessage(Text.mm("  <gray>경계: 중심 ${border.center.blockX}, ${border.center.blockZ} / 한 변 ${border.size.toInt()}블록"))
        sender.sendMessage(Text.mm("  <gray>워프: ${c.warpMode.name.lowercase()}, 후보 지점 ${c.spawnPoints(plugin.zones.play).size}개, 최소 거리 ${c.minPlayerDistance.toInt()}"))
        sender.sendMessage(Text.mm("  <gray>플레이존 인원: ${plugin.zones.play.players.size}명"))
    }

    private fun admin(sender: CommandSender): Boolean {
        if (sender.hasPermission("randomwars.admin")) return true
        sender.sendMessage(Text.prefix("<red>권한이 없습니다."))
        return false
    }

    private fun usage(sender: CommandSender) {
        sender.sendMessage(Text.prefix("<gray>/rw version | reload | box give|sim | weapon list|give | spawn add|list|remove|tp | zone | region add|list|remove|tp | supply now|status"))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val players = { Bukkit.getOnlinePlayers().map(Player::getName) }
        val boxes = listOf("일반", "보급")
        val options = when (args.size) {
            1 -> listOf("version", "reload", "box", "weapon", "spawn", "zone", "region", "supply")
            2 -> when (args[0].lowercase()) {
                "box" -> listOf("give", "sim")
                "weapon" -> listOf("list", "give")
                "spawn" -> listOf("add", "list", "remove", "tp")
                "region" -> listOf("add", "list", "remove", "tp")
                "supply" -> listOf("now", "status")
                else -> emptyList()
            }
            3 -> when ("${args[0]} ${args[1]}".lowercase()) {
                "box give", "weapon give" -> players()
                "box sim" -> boxes
                "region remove", "region tp" -> plugin.regions.regions.map { it.name }
                else -> emptyList()
            }
            4 -> when ("${args[0]} ${args[1]}".lowercase()) {
                "box give" -> boxes
                "weapon give" -> plugin.weapons.all.map { it.id }
                else -> emptyList()
            }
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }
    }
}
