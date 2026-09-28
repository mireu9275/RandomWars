package com.randomwars.weapon

enum class Grade(val displayName: String, val color: String) {
    COMMON("일반", "<white>"),
    RARE("희귀", "<aqua>"),
    EPIC("영웅", "<light_purple>"),
    LEGENDARY("전설", "<gold>");

    val colored: String get() = "$color$displayName"

    companion object {
        fun parse(value: String): Grade? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
