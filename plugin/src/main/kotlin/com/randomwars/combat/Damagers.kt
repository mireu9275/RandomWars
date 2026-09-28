package com.randomwars.combat

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed

object Damagers {
    /** 피해를 준 엔티티를 실제 플레이어로 거슬러 올라간다 (화살, 삼지창, TNT 등) */
    fun playerOf(damager: Entity?): Player? = when (damager) {
        is Player -> damager
        is Projectile -> damager.shooter as? Player
        is TNTPrimed -> damager.source as? Player
        else -> null
    }
}
