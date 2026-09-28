package com.randomwars.skill

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID

/** 스킬 투사체. 눈덩이에 외형 아이템을 씌워 쏘고, 맞으면 on-hit 부품을 실행한다. */
class ProjectileTracker(private val plugin: Plugin, private val effects: SkillEffects) : Listener {

    private class Shot(val caster: UUID, val damage: Double, val onHit: List<SkillPart>, val born: Long)

    private val key = NamespacedKey(plugin, "skill_projectile")
    private val shots = mutableMapOf<UUID, Shot>()

    fun launch(caster: Player, velocity: Vector, gravity: Boolean, item: Material, damage: Double, onHit: List<SkillPart>) {
        val ball = caster.launchProjectile(Snowball::class.java, velocity)
        ball.item = ItemStack(item)
        ball.setGravity(gravity)
        ball.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
        shots[ball.uniqueId] = Shot(caster.uniqueId, damage, onHit, System.currentTimeMillis())
        // 중력 없는 투사체가 끝없이 날아가지 않게 3초 뒤 제거
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (ball.isValid) ball.remove()
            shots.remove(ball.uniqueId)
        }, 60L)
    }

    @EventHandler
    fun onHit(event: ProjectileHitEvent) {
        val shot = shots.remove(event.entity.uniqueId) ?: return
        val caster = plugin.server.getPlayer(shot.caster) ?: return
        val target = (event.hitEntity as? LivingEntity)?.takeIf { it != caster }
        if (event.hitEntity == caster) {
            event.isCancelled = true
            return
        }
        val origin = target?.location ?: event.hitBlock?.location?.add(0.5, 1.0, 0.5) ?: event.entity.location
        origin.world.spawnParticle(Particle.CRIT, origin.clone().add(0.0, 1.0, 0.0), 10, 0.2, 0.2, 0.2, 0.1)
        if (target != null) Parts.hit(caster, target, shot.damage)
        val ctx = SkillContext(caster, target, origin, effects, this)
        for (part in shot.onHit) {
            if (part.needsTarget && target == null) continue
            part.apply(ctx)
        }
    }
}
