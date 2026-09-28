package com.randomwars

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage

object Text {
    private val mm = MiniMessage.miniMessage()

    fun mm(text: String): Component = mm.deserialize(text)

    /** 아이템 이름/로어용: 바닐라 기본 기울임을 끈다. */
    fun item(text: String): Component = mm(text).decoration(TextDecoration.ITALIC, false)

    fun prefix(text: String): Component = mm("<dark_gray>[<gold>RW</gold>]</dark_gray> $text")
}
