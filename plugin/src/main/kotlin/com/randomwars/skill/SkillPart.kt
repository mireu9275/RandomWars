package com.randomwars.skill

import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

/**
 * 스킬 실행 문맥. target 은 조준한 대상(없을 수 있음), origin 은 효과 중심(투사체 명중 지점 등).
 */
class SkillContext(
    val caster: Player,
    val target: LivingEntity?,
    val origin: Location,
    val effects: SkillEffects,
    val projectiles: ProjectileTracker,
)

/** 재사용 가능한 스킬 부품. 무기는 YAML 에서 부품과 수치를 조합해 정의한다. */
interface SkillPart {
    /** 조준 대상이 있어야 쓸 수 있는 부품이면 true (대상이 없으면 스킬이 나가지 않고 쿨다운도 돌지 않는다) */
    val needsTarget: Boolean get() = false

    fun apply(ctx: SkillContext)
}

data class SkillDefinition(
    val name: String,
    val cooldownSeconds: Double,
    /** 조준 대상을 찾는 최대 거리 */
    val range: Double,
    val parts: List<SkillPart>,
) {
    val needsTarget get() = parts.any { it.needsTarget }
}
