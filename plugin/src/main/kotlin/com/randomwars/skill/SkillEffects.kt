package com.randomwars.skill

import com.randomwars.Text
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * 시간이 지나면 풀리는 상태 효과: 보호막(피해 흡수), 표식(받는 피해 증가), 기절(이동·스킬 불가).
 */
class SkillEffects(private val plugin: Plugin) : Listener {

    private class Shield(var amount: Double, val until: Long)
    private class Mark(val multiplier: Double, val until: Long)

    private val shields = mutableMapOf<UUID, Shield>()
    private val marks = mutableMapOf<UUID, Mark>()
    private val stuns = mutableMapOf<UUID, Long>()

    fun start() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 5L, 5L)
    }

    private fun now() = System.currentTimeMillis()

    fun addShield(entity: LivingEntity, amount: Double, seconds: Double) {
        shields[entity.uniqueId] = Shield(amount, now() + (seconds * 1000).toLong())
    }

    fun shieldOf(entity: LivingEntity): Double = shields[entity.uniqueId]?.takeIf { it.until > now() }?.amount ?: 0.0

    fun mark(entity: LivingEntity, multiplier: Double, seconds: Double) {
        marks[entity.uniqueId] = Mark(multiplier, now() + (seconds * 1000).toLong())
        entity.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, (seconds * 20).toInt(), 0, false, false))
    }

    fun stun(entity: LivingEntity, seconds: Double) {
        stuns[entity.uniqueId] = now() + (seconds * 1000).toLong()
        val ticks = (seconds * 20).toInt()
        entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, ticks, 9, false, false))
        entity.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false))
        (entity as? Player)?.sendActionBar(Text.mm("<red>기절!"))
    }

    fun isStunned(entity: LivingEntity) = (stuns[entity.uniqueId] ?: 0) > now()

    private fun tick() {
        val t = now()
        shields.entries.removeIf { (uuid, s) ->
            val expired = s.until <= t || s.amount <= 0
            if (!expired) Bukkit.getEntity(uuid)?.let {
                it.world.spawnParticle(Particle.ENCHANTED_HIT, it.location.add(0.0, 1.0, 0.0), 6, 0.4, 0.6, 0.4, 0.0)
            }
            expired
        }
        marks.entries.removeIf { it.value.until <= t }
        stuns.entries.removeIf { it.value <= t }
    }

    /** 표식 → 보호막 순서로 적용: 표식으로 커진 피해를 보호막이 먼저 받아낸다 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity as? LivingEntity ?: return
        val t = now()
        marks[entity.uniqueId]?.takeIf { it.until > t }?.let { event.damage *= it.multiplier }

        val shield = shields[entity.uniqueId]?.takeIf { it.until > t } ?: return
        val absorbed = minOf(shield.amount, event.damage)
        shield.amount -= absorbed
        event.damage -= absorbed
        entity.world.playSound(entity.location, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.2f)
        if (shield.amount <= 0) {
            entity.world.playSound(entity.location, Sound.ITEM_SHIELD_BREAK, 1f, 1f)
            (entity as? Player)?.sendActionBar(Text.mm("<gray>보호막이 깨졌습니다"))
        }
        if (event.damage <= 0) event.isCancelled = true
    }

    /** 기절 중에는 제자리 (시선은 돌릴 수 있다) */
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!isStunned(event.player)) return
        val from = event.from
        val to = event.to
        if (from.x != to.x || from.z != to.z || to.y > from.y) {
            event.to = from.clone().apply { yaw = to.yaw; pitch = to.pitch }
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val id = event.player.uniqueId
        shields.remove(id); marks.remove(id); stuns.remove(id)
    }
}
