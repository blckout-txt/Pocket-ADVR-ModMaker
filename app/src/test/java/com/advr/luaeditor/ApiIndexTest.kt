package com.advr.luaeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiIndexTest {

    @Test
    fun `index carries the full stub surface`() {
        val api = TestApi.index
        assertEquals("1.101.0", api.version)
        assertTrue("expected hundreds of classes", api.classes.size > 380)
        assertTrue("expected hundreds of globals", api.globals.size > 380)
    }

    @Test
    fun `per-file globals resolve to their classes`() {
        val api = TestApi.index
        assertEquals("pickup", api.globalType("pickup"))
        assertEquals("potion", api.globalType("potion"))
        assertEquals("ADVREvents", api.globalType("ADVR"))
        assertEquals("PotionEvents", api.globalType("ADVR.PotionEvents"))
    }

    @Test
    fun `fields and methods are attached to the right class`() {
        val api = TestApi.index
        val name = api.findField("pickup", "name")
        assertNotNull(name)
        assertTrue(name!!.type.contains("string"))

        val spawnsIn = api.findField("pickup", "spawnsIn")
        assertEquals("string[]", spawnsIn?.type)

        val register = api.findMethods("pickup", "RegisterItem")
        assertTrue("pickup.RegisterItem should exist", register.isNotEmpty())
    }

    @Test
    fun `overloads are all kept`() {
        val api = TestApi.index
        val overloads = api.findMethods("achievement", "SendRPCEvent")
        assertTrue("expected several SendRPCEvent overloads", overloads.size > 3)
        assertTrue(overloads.all { it.doc.contains("RPC") })
    }

    @Test
    fun `class hierarchy is walked for inherited members`() {
        val api = TestApi.index
        // `pickup : ItemInterpreter_PickupDiskRepresentation`
        assertEquals("ItemInterpreter_PickupDiskRepresentation", api.classes["pickup"]?.parent)
        assertTrue(api.fieldsOf("pickup").size >= api.classes["pickup"]!!.fields.size)
    }

    @Test
    fun `event fields keep their function signature`() {
        val api = TestApi.index
        val hit = api.findField("ADVREvents", "onPlayerHit")
        assertNotNull(hit)
        assertTrue(hit!!.isCallable)
        val params = com.advr.luaeditor.api.TypeNames.functionParams(hit.type)
        assertEquals(listOf("damage", "damageSource", "receivedDamageType", "hitPosition", "isStatsProbe"),
            params.map { it.name })
        assertEquals("number", com.advr.luaeditor.api.TypeNames.functionReturn(hit.type))
    }

    @Test
    fun `union types collapse to their primary branch`() {
        val t = com.advr.luaeditor.api.TypeNames
        assertEquals("string", t.primary("string|nil"))
        assertEquals("GameObject", t.primary("GameObject[]"))
        assertEquals("GameObject", t.element("GameObject[]"))
        assertEquals("function", t.primary("fun(a: X):Y"))
    }
}
