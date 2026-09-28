package com.randomwars.zone

import com.randomwars.combat.Damagers
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

class ZoneListener(private val zones: ZoneService, private val mobSpawning: () -> Boolean) : Listener {

    /** 몬스터가 스킬 조준을 가로채지 않게, 설정이 꺼져 있으면 플레이존의 기존 몬스터를 치운다 */
    @EventHandler
    fun onEntitiesLoad(event: org.bukkit.event.world.EntitiesLoadEvent) {
        if (event.world != zones.play || mobSpawning()) return
        event.entities.filterIsInstance<org.bukkit.entity.Enemy>().forEach { it.remove() }
    }

    /** 플레이존에 살아 있는 채로 나갔던 사람만 제자리, 나머지는 모두 로비에서 시작 */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!zones.isInPlay(event.player)) zones.sendToLobby(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        zones.cancelLobby(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        event.respawnLocation = zones.lobbySpawn()
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (victim.world == zones.lobby || zones.isInvulnerable(victim)) {
            event.isCancelled = true
        }
    }

    /** 무적 중에 먼저 때리면 무적이 풀린다 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        Damagers.playerOf(event.damager)?.let { zones.clearInvulnerable(it) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFood(event: FoodLevelChangeEvent) {
        if (event.entity.world == zones.lobby) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (event.block.world == zones.lobby && !event.player.hasPermission("randomwars.admin")) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.block.world == zones.lobby && !event.player.hasPermission("randomwars.admin")) event.isCancelled = true
    }
}
