package com.randomwars.combat

import com.randomwars.Text
import com.randomwars.zone.ZoneService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 규칙 3: 플레이어끼리 피해를 주고받으면 전투 상태.
 * 전투 중에는 엔더상자와 /로비를 쓸 수 없고, 접속을 끊으면 사망 처리한다.
 */
class CombatTracker(
    private val plugin: Plugin,
    private val zones: ZoneService,
    private val tagSeconds: () -> Int,
) : Listener {

    private val taggedUntil = mutableMapOf<UUID, Long>()
    private val lastAttacker = mutableMapOf<UUID, UUID>()

    fun start() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 5L, 5L)
    }

    fun remainingSeconds(player: Player): Int {
        val until = taggedUntil[player.uniqueId] ?: return 0
        val ms = until - System.currentTimeMillis()
        return if (ms > 0) ((ms + 999) / 1000).toInt() else 0
    }

    fun isInCombat(player: Player) = remainingSeconds(player) > 0

    /** /로비 등에서 쓰는 차단 사유. 전투 중이 아니면 null */
    fun blockedReason(player: Player): String? =
        if (isInCombat(player)) "전투 중에는 로비로 갈 수 없습니다. (${remainingSeconds(player)}초)" else null

    private fun tag(player: Player) {
        val wasInCombat = isInCombat(player)
        taggedUntil[player.uniqueId] = System.currentTimeMillis() + tagSeconds() * 1000L
        if (!wasInCombat) zones.cancelLobby(player)
    }

    private fun clear(player: Player) {
        taggedUntil.remove(player.uniqueId)
        lastAttacker.remove(player.uniqueId)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val iter = taggedUntil.entries.iterator()
        while (iter.hasNext()) {
            val (uuid, until) = iter.next()
            val player = Bukkit.getPlayer(uuid)
            if (player == null) {
                iter.remove()
                continue
            }
            if (now >= until) {
                iter.remove()
                lastAttacker.remove(uuid)
                player.sendActionBar(Text.mm("<green>전투 상태가 해제되었습니다."))
            } else {
                player.sendActionBar(Text.mm("<red>⚔ 전투 중 <white>${remainingSeconds(player)}초</white> <gray>| 엔더상자·/로비 사용 불가"))
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = Damagers.playerOf(event.damager) ?: return
        if (attacker == victim || !zones.isInPlay(victim)) return
        tag(victim)
        tag(attacker)
        lastAttacker[victim.uniqueId] = attacker.uniqueId
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.clickedBlock?.type != Material.ENDER_CHEST) return
        if (!isInCombat(event.player)) return
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        event.player.sendActionBar(Text.mm("<red>전투 중에는 엔더상자를 열 수 없습니다. (${remainingSeconds(event.player)}초)"))
    }

    /** 다른 경로(명령어 플러그인 등)로 여는 것도 막는다 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (event.inventory.type == InventoryType.ENDER_CHEST && isInCombat(player)) event.isCancelled = true
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        clear(event.player)
    }

    /** 전투 로그아웃: 사망 처리하고 마지막 공격자를 처치자로 둔다 (처치 보상은 DeathRules 가 준다) */
    @EventHandler(priority = EventPriority.LOW)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        if (!isInCombat(player) || !zones.isInPlay(player) || player.isDead) {
            clear(player)
            return
        }
        lastAttacker[player.uniqueId]?.let { Bukkit.getPlayer(it) }?.let { player.killer = it }
        Bukkit.broadcast(Text.prefix("<yellow>${player.name}</yellow>님이 전투 중 접속을 끊어 사망 처리되었습니다."))
        player.health = 0.0
        clear(player)
    }
}
