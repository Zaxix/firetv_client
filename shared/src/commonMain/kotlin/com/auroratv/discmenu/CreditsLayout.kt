package com.auroratv.discmenu

import com.auroratv.jellyfin.model.PersonDto

/** Turns the server's people into the lines each credits page shows. Pure, so it can be tested without a screen. */
object CreditsLayout {
    private const val SEPARATOR = "...."
    private val crewOrder = listOf("Director" to "Directed by", "Writer" to "Written by", "Producer" to "Produced by", "Composer" to "Music by")

    /** Lines per credits page; a page missing from the result keeps its baked plate (the server has nothing for it). */
    fun build(people: List<PersonDto>, def: CreditsDef): Map<String, List<String>> {
        val actors = people.filter { it.type.equals("Actor", ignoreCase = true) && it.name.isNotBlank() }
        val cast = actors.map { a -> a.role?.trim()?.takeIf { it.isNotEmpty() }?.let { "${a.name}$SEPARATOR$it" } ?: a.name }
        val crew = crewOrder.mapNotNull { (type, label) ->
            val names = people.filter { it.type.equals(type, ignoreCase = true) && it.name.isNotBlank() }.map { it.name }.distinct()
            names.takeIf { it.isNotEmpty() }?.let { "$label ${it.joinToString(", ")}" }
        }
        val out = LinkedHashMap<String, List<String>>()
        var next = 0
        for ((id, page) in def.pages) {
            when (page.kind) {
                "cast" -> if (cast.isNotEmpty()) {
                    out[id] = cast.drop(next).take(page.capacity)
                    next += page.capacity
                }
                "crew" -> if (crew.isNotEmpty()) out[id] = crew.take(page.capacity)
            }
        }
        return out
    }
}
