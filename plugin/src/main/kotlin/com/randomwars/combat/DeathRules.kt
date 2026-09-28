package com.randomwars.combat

import com.randomwars.Text
import com.randomwars.box.BoxType
import com.randomwars.box.LootTable
import com.randomwars.zone.ZoneConfig
import com.randomwars.zone.ZoneService
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID

/**
 * 규칙 2 (처치 보상)와 규칙 5 (사망 시 100% 소멸).
 */
class DeathRules(
    private val config: ZoneConfig,
    private val zones: ZoneService,
    private val loot: LootTable,
) : Listener {

    private data class Hit(val attacker: UUID, val at: Long)

    /** 피해자 → 최근 공격자 */
    private val lastHit = mutableMapOf<UUID, Hit>()

    /** 처치자 → (피해자 → 마지막 보상 시각) */
    private val rewarded = mutableMapOf<UUID, MutableMap<UUID, Long>>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = Damagers.playerOf(event.damager) ?: return
        if (attacker == victim) return
        lastHit[victim.uniqueId] = Hit(attacker.uniqueId, System.currentTimeMillis())
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.player

        // 규칙 5: 드롭 없이 전부 소멸 (랜덤상자 포함)
        event.keepInventory = false
        event.drops.clear()
        event.keepLevel = false
        event.droppedExp = 0
        event.newExp = 0
        event.newLevel = 0
        event.newTotalExp = 0
        zones.resetLife(victim)

        val killer = findKiller(victim)
        lastHit.remove(victim.uniqueId)
        if (killer != null) reward(killer, victim)
    }

    private fun findKiller(victim: Player): Player? {
        victim.killer?.takeIf { it != victim }?.let { return it }
        val hit = lastHit[victim.uniqueId] ?: return null
        if (System.currentTimeMillis() - hit.at > config.assistSeconds * 1000L) return null
        return Bukkit.getPlayer(hit.attacker)
    }

    private fun reward(killer: Player, victim: Player) {
        val now = System.currentTimeMillis()
        val history = rewarded.getOrPut(killer.uniqueId) { mutableMapOf() }
        val last = history[victim.uniqueId]
        if (last != null && now - last < config.sameVictimCooldownSeconds * 1000L) {
            killer.sendMessage(Text.prefix("<gray>같은 상대를 ${config.sameVictimCooldownSeconds / 60}분 안에 다시 처치해 보상이 없습니다."))
            return
        }
        history[victim.uniqueId] = now
        val boxes = loot.createBox(BoxType.NORMAL, config.killRewardBoxes)
        killer.inventory.addItem(boxes).values.forEach { killer.world.dropItemNaturally(killer.location, it) }
        killer.sendMessage(Text.prefix("<yellow>${victim.name}</yellow>님을 처치해 <white>랜덤상자 ${config.killRewardBoxes}개</white>를 받았습니다."))
        killer.playSound(killer.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f)
    }
}
