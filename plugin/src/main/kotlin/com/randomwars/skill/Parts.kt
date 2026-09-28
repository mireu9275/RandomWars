package com.randomwars.skill

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

/**
 * 스킬 부품 모음. YAML 의 `type` 으로 고르고 나머지 키가 수치다.
 * 예) { type: flurry, hits: 6, damage: 1.5, interval: 2 }
 */
object Parts {
    lateinit var plugin: Plugin

    fun parse(raw: Map<*, *>): SkillPart {
        val p = Params(raw)
        return when (val type = p.str("type", "")) {
            "damage" -> Damage(p.num("damage", 4.0))
            "dash" -> Dash(p.num("power", 1.6), p.num("damage", 4.0), p.num("knockback", 0.6))
            "blink_behind" -> BlinkBehind(p.num("distance", 1.3))
            "flurry" -> Flurry(p.int("hits", 5), p.num("damage", 1.5), p.int("interval", 3), p.num("reach", 5.0))
            "stun" -> Stun(p.num("seconds", 1.5))
            "slow" -> Slow(p.num("seconds", 3.0), p.int("amplifier", 2), p.num("radius", 0.0))
            "knockback" -> Knockback(p.num("power", 1.2), p.num("radius", 3.0), p.num("damage", 0.0))
            "projectile" -> Projectile(
                speed = p.num("speed", 2.0),
                gravity = p.bool("gravity", false),
                count = p.int("count", 1),
                spread = p.num("spread", 6.0),
                item = Material.matchMaterial(p.str("item", "SNOWBALL")) ?: Material.SNOWBALL,
                damage = p.num("damage", 4.0),
                onHit = p.list("on-hit").map { parse(it) },
            )
            "explosion" -> Explosion(p.num("radius", 3.0), p.num("damage", 5.0), p.num("knockback", 0.8), p.str("at", "origin"), p.num("range", 20.0))
            "shield" -> Shield(p.num("amount", 8.0), p.num("seconds", 5.0))
            "mark" -> Mark(p.num("multiplier", 1.25), p.num("seconds", 5.0))
            else -> error("알 수 없는 스킬 부품: $type")
        }
    }

    class Params(private val raw: Map<*, *>) {
        fun str(key: String, def: String) = raw[key]?.toString() ?: def
        fun num(key: String, def: Double) = (raw[key] as? Number)?.toDouble() ?: def
        fun int(key: String, def: Int) = (raw[key] as? Number)?.toInt() ?: def
        fun bool(key: String, def: Boolean) = raw[key] as? Boolean ?: def
        fun list(key: String): List<Map<*, *>> = (raw[key] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    }

    // ---------- 공용 ----------

    fun hit(caster: Player, target: LivingEntity, damage: Double) {
        if (damage <= 0 || target.isDead) return
        target.damage(damage, caster)
    }

    fun push(entity: LivingEntity, from: Location, power: Double) {
        if (power <= 0) return
        val dir = entity.location.toVector().subtract(from.toVector()).setY(0)
        if (dir.lengthSquared() < 0.01) dir.copy(from.direction.setY(0))
        entity.velocity = entity.velocity.add(dir.normalize().multiply(power).setY(0.35 * power.coerceAtMost(1.5)))
    }

    fun victimsAround(center: Location, radius: Double, caster: Player): List<LivingEntity> =
        center.world.getNearbyLivingEntities(center, radius) { it != caster && !it.isDead && it.type.isAlive }.toList()

    // ---------- 부품 ----------

    /** 타격: 대상에게 바로 피해 */
    class Damage(private val damage: Double) : SkillPart {
        override val needsTarget = true
        override fun apply(ctx: SkillContext) {
            val t = ctx.target ?: return
            hit(ctx.caster, t, damage)
            t.world.spawnParticle(Particle.CRIT, t.location.add(0.0, 1.0, 0.0), 12, 0.3, 0.4, 0.3, 0.1)
        }
    }

    /** 돌진: 바라보는 방향으로 튀어나가며 경로의 적에게 피해. 둔화 상태면 거의 나가지 않는다 (돌진 ↔ 둔화) */
    class Dash(private val power: Double, private val damage: Double, private val knockback: Double) : SkillPart {
        override fun apply(ctx: SkillContext) {
            val p = ctx.caster
            val slowed = p.hasPotionEffect(PotionEffectType.SLOWNESS)
            val actual = if (slowed) power * 0.3 else power
            p.velocity = p.location.direction.setY(0).normalize().multiply(actual).setY(0.25)
            p.world.playSound(p.location, Sound.ENTITY_BREEZE_JUMP, 1f, 1f)
            if (slowed) p.sendActionBar(net.kyori.adventure.text.Component.text("둔화 상태라 돌진이 약해졌습니다"))
            val hitOnce = mutableSetOf<LivingEntity>()
            var ticks = 0
            Bukkit.getScheduler().runTaskTimer(plugin, { task ->
                if (ticks++ >= 10 || !p.isOnline || p.isDead) {
                    task.cancel(); return@runTaskTimer
                }
                p.world.spawnParticle(Particle.CLOUD, p.location, 3, 0.2, 0.1, 0.2, 0.01)
                for (v in victimsAround(p.location, 1.6, p)) {
                    if (hitOnce.add(v)) {
                        hit(p, v, damage)
                        push(v, p.location, knockback)
                    }
                }
            }, 1L, 1L)
        }
    }

    /** 등 뒤 이동: 조준한 대상의 등 뒤로 순간이동해 대상을 바라본다 */
    class BlinkBehind(private val distance: Double) : SkillPart {
        override val needsTarget = true
        override fun apply(ctx: SkillContext) {
            val t = ctx.target ?: return
            val back = t.location.direction.setY(0)
            if (back.lengthSquared() < 0.01) back.copy(Vector(0, 0, 1))
            val dest = t.location.clone().subtract(back.normalize().multiply(distance))
            if (!dest.block.isPassable || !dest.clone().add(0.0, 1.0, 0.0).block.isPassable) {
                dest.x = t.location.x; dest.z = t.location.z  // 뒤가 막혀 있으면 대상 위치로
            }
            dest.direction = t.location.toVector().subtract(dest.toVector())
            val p = ctx.caster
            p.world.spawnParticle(Particle.LARGE_SMOKE, p.location.add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.01)
            p.teleport(dest)
            p.world.spawnParticle(Particle.LARGE_SMOKE, dest.clone().add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.01)
            p.world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.4f)
        }
    }

    /** 연타: 짧은 시간에 여러 번 때린다. 대상과 가까이 있어야 들어간다 (등 뒤 이동과 연계) */
    class Flurry(private val hits: Int, private val damage: Double, private val interval: Int, private val reach: Double) : SkillPart {
        override val needsTarget = true
        override fun apply(ctx: SkillContext) {
            val t = ctx.target ?: return
            val p = ctx.caster
            var done = 0
            Bukkit.getScheduler().runTaskTimer(plugin, { task ->
                if (done++ >= hits || t.isDead || !p.isOnline || p.isDead || t.world != p.world ||
                    t.location.distance(p.location) > reach) {
                    task.cancel(); return@runTaskTimer
                }
                t.noDamageTicks = 0
                // 연타가 넉백으로 대상을 밀어내 끊기지 않도록 수평 넉백은 없앤다
                val before = t.velocity
                hit(p, t, damage)
                t.velocity = Vector(before.x, t.velocity.y.coerceAtMost(0.1), before.z)
                t.world.spawnParticle(Particle.SWEEP_ATTACK, t.location.add(0.0, 1.0, 0.0), 1)
                t.world.playSound(t.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.3f + done * 0.05f)
            }, 0L, interval.toLong())
        }
    }

    class Stun(private val seconds: Double) : SkillPart {
        override val needsTarget = true
        override fun apply(ctx: SkillContext) {
            val t = ctx.target ?: return
            ctx.effects.stun(t, seconds)
            t.world.spawnParticle(Particle.FLASH, t.location.add(0.0, 2.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0, org.bukkit.Color.YELLOW)
        }
    }

    /** 둔화: radius 가 0 이면 대상 하나, 아니면 효과 중심 주변 전체 */
    class Slow(private val seconds: Double, private val amplifier: Int, private val radius: Double) : SkillPart {
        override val needsTarget get() = radius <= 0
        override fun apply(ctx: SkillContext) {
            val victims = if (radius > 0) victimsAround(ctx.origin, radius, ctx.caster) else listOfNotNull(ctx.target)
            for (v in victims) {
                v.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, (seconds * 20).toInt(), amplifier))
                v.world.spawnParticle(Particle.SNOWFLAKE, v.location.add(0.0, 1.0, 0.0), 20, 0.4, 0.6, 0.4, 0.02)
            }
        }
    }

    /** 넉백 파동: 효과 중심 주변을 밀어낸다 */
    class Knockback(private val power: Double, private val radius: Double, private val damage: Double) : SkillPart {
        override fun apply(ctx: SkillContext) {
            val center = ctx.origin
            center.world.spawnParticle(Particle.GUST, center.clone().add(0.0, 1.0, 0.0), 6, radius / 2, 0.3, radius / 2, 0.0)
            center.world.playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f)
            for (v in victimsAround(center, radius, ctx.caster)) {
                hit(ctx.caster, v, damage)
                push(v, center, power)
            }
        }
    }

    /** 투사체: 명중하면 on-hit 부품들을 명중 대상/지점에 적용한다 */
    class Projectile(
        private val speed: Double,
        private val gravity: Boolean,
        private val count: Int,
        private val spread: Double,
        private val item: Material,
        private val damage: Double,
        private val onHit: List<SkillPart>,
    ) : SkillPart {
        override fun apply(ctx: SkillContext) {
            val p = ctx.caster
            repeat(count) { i ->
                val dir = p.eyeLocation.direction
                if (count > 1) dir.rotateAroundY(Math.toRadians(spread * (i - (count - 1) / 2.0)))
                ctx.projectiles.launch(p, dir.multiply(speed), gravity, item, damage, onHit)
            }
            p.world.playSound(p.location, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.7f)
        }
    }

    /** 범위 폭발 (지형 파괴 없음). at: origin | self | front | look */
    class Explosion(
        private val radius: Double,
        private val damage: Double,
        private val knockback: Double,
        private val at: String,
        private val range: Double,
    ) : SkillPart {
        override fun apply(ctx: SkillContext) {
            val p = ctx.caster
            val center = when (at) {
                "self" -> p.location
                "front" -> p.location.add(p.location.direction.setY(0).normalize().multiply(2.0))
                "look" -> p.rayTraceBlocks(range, FluidCollisionMode.NEVER)?.hitPosition?.toLocation(p.world)
                    ?: p.eyeLocation.add(p.eyeLocation.direction.multiply(range))
                else -> ctx.origin
            }
            center.world.spawnParticle(Particle.EXPLOSION, center, 3, radius / 3, 0.3, radius / 3, 0.0)
            center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.1f)
            for (v in victimsAround(center, radius, p)) {
                v.noDamageTicks = 0
                hit(p, v, damage)
                push(v, center, knockback)
            }
        }
    }

    /** 보호막: 일정 시간 동안 받는 피해를 amount 만큼 흡수 (연타 ↔ 보호막) */
    class Shield(private val amount: Double, private val seconds: Double) : SkillPart {
        override fun apply(ctx: SkillContext) {
            ctx.effects.addShield(ctx.caster, amount, seconds)
            ctx.caster.world.playSound(ctx.caster.location, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 1f)
            ctx.caster.world.spawnParticle(Particle.TOTEM_OF_UNDYING, ctx.caster.location.add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.1)
        }
    }

    /** 표식: 대상이 받는 모든 피해 증가, 발광 */
    class Mark(private val multiplier: Double, private val seconds: Double) : SkillPart {
        override val needsTarget = true
        override fun apply(ctx: SkillContext) {
            val t = ctx.target ?: return
            ctx.effects.mark(t, multiplier, seconds)
            t.world.playSound(t.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.6f)
        }
    }
}
