package com.randomwars.command

import com.randomwars.Text
import com.randomwars.zone.ZoneService
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** /입장, /로비 */
class ZoneCommands(private val zones: ZoneService) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(Text.prefix("<red>플레이어만 쓸 수 있습니다."))
            return true
        }
        when (command.name) {
            "입장" -> zones.enter(player)
            // 전투 상태 차단은 M3에서 blockedReason 에 연결한다
            "로비" -> zones.requestLobby(player) { null }
        }
        return true
    }
}
