package com.auroratv

import com.auroratv.brands.BrandCatalog
import com.auroratv.jellyfin.model.BaseItemDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrandsTest {
    private fun studio(id: String, name: String, images: Map<String, String> = emptyMap()) = BaseItemDto(id = id, name = name, type = "Studio", imageTags = images)

    @Test
    fun studioNamesMatchLooselyAndGroupIntoBrands() {
        val studios = listOf(
            studio("1", "Apple TV+"), studio("2", "Apple Original Films"), studio("3", "NETFLIX"),
            studio("4", "Paramount+"), studio("5", "Paramount Pictures", mapOf("Logo" to "t")),
            studio("6", "Marvel Studios"), studio("7", "Some Indie Co"), studio("8", "Lucasfilm Ltd."),
        )
        val brands = BrandCatalog.resolve(studios)
        assertEquals(listOf("netflix", "marvel", "starwars", "appletv", "paramount"), brands.map { it.brand.id }, "catalog order, only brands present")
        assertEquals(listOf("1", "2"), brands.first { it.brand.id == "appletv" }.studioIds)
        val paramount = brands.first { it.brand.id == "paramount" }
        assertEquals(setOf("4", "5"), paramount.studioIds.toSet())
        assertEquals("5", paramount.imageStudio?.id, "the server's studio logo is used when there is one")
        assertEquals("Logo", paramount.imageType)
        assertNull(brands.first { it.brand.id == "netflix" }.imageStudio, "otherwise the name is drawn in Aurora's type")
    }

    @Test
    fun normalisation() {
        assertEquals("appletvplus", BrandCatalog.normalize("Apple TV+"))
        assertEquals(BrandCatalog.normalize("Lucasfilm Ltd."), BrandCatalog.normalize("lucasfilm ltd"))
        assertTrue(BrandCatalog.all.map { it.id }.containsAll(listOf("netflix", "appletv", "paramount", "disney", "pixar", "marvel", "starwars", "hulu", "natgeo")))
    }
}
