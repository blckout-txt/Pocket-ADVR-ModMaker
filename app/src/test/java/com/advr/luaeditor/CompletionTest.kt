package com.advr.luaeditor

import com.advr.luaeditor.api.ModKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionTest {

    private val relicPath = "MyMod/items/thorn_relic/thorn_relic.lua"
    private val orbPath = "MyMod/potions/fizz.lua"

    @Test
    fun `member access on the file's own global lists its stub fields`() {
        val r = TestApi.completeAt("function ADVR.onLoad()\n\tpickup.|\nend\n", relicPath)
        assertTrue(r.isMemberAccess)
        val labels = TestApi.labels(r)
        assertTrue("expected pickup.name", "name" in labels)
        assertTrue("expected pickup.desc", "desc" in labels)
        assertTrue("expected pickup.spawnsIn", "spawnsIn" in labels)
        assertTrue("expected pickup.RegisterItem", "RegisterItem" in labels)
    }

    @Test
    fun `prefix narrows the member list`() {
        val r = TestApi.completeAt("pickup.spa|", relicPath)
        assertEquals("spawnsIn", TestApi.labels(r).first())
    }

    @Test
    fun `method completions insert a call and carry a signature`() {
        val r = TestApi.completeAt("pickup.CallFunctionIn|", relicPath)
        val item = r.items.first { it.label == "CallFunctionIn" }
        assertTrue(item.insertText.startsWith("CallFunctionIn("))
        assertTrue(item.detail.contains("method"))
    }

    @Test
    fun `chained calls resolve through the declared return type`() {
        // `game.GetLocalPlayer()` returns a PlayerRef; the next dot must offer PlayerRef members.
        val method = TestApi.index.findMethods("game", "GetLocalPlayer").firstOrNull()
        assertNotNull("game.GetLocalPlayer should exist in the stubs", method)
        val returned = com.advr.luaeditor.api.TypeNames.primary(method!!.ret)
        val expected = TestApi.index.fieldsOf(returned).map { it.name } +
            TestApi.index.methodsOf(returned).map { it.first().name }
        val r = TestApi.completeAt("local p = game.GetLocalPlayer()\np.|\n", relicPath)
        assertTrue("expected members of $returned", TestApi.labels(r).any { it in expected })
    }

    @Test
    fun `locals typed from a constructor expose that type`() {
        val r = TestApi.completeAt("local v = vector3.__new(1, 2, 3)\nv.|\n", relicPath)
        val labels = TestApi.labels(r)
        assertTrue("expected vector components, got $labels", "x" in labels && "y" in labels && "z" in labels)
    }

    // ---------------------------------------------------------------- custom objects

    @Test
    fun `a table built field by field completes its own fields`() {
        val src = """
            local state = {}
            state.charges = 3
            state.owner = "player"
            state.|
        """.trimIndent()
        val labels = TestApi.labels(TestApi.completeAt(src, relicPath))
        assertTrue("expected charges, got $labels", "charges" in labels)
        assertTrue("expected owner, got $labels", "owner" in labels)
    }

    @Test
    fun `a table literal completes its keys with inferred types`() {
        val src = """
            local config = { power = 5, label = "hi", enabled = true }
            config.|
        """.trimIndent()
        val r = TestApi.completeAt(src, relicPath)
        val labels = TestApi.labels(r)
        assertTrue(labels.containsAll(listOf("power", "label", "enabled")))
        assertEquals("number", r.items.first { it.label == "power" }.detail)
        assertEquals("string", r.items.first { it.label == "label" }.detail)
        assertEquals("boolean", r.items.first { it.label == "enabled" }.detail)
    }

    @Test
    fun `nested tables resolve through several dots`() {
        val src = """
            local mod = {}
            mod.stats = {}
            mod.stats.damage = 12
            mod.stats.|
        """.trimIndent()
        val labels = TestApi.labels(TestApi.completeAt(src, relicPath))
        assertTrue("expected damage, got $labels", "damage" in labels)
    }

    @Test
    fun `functions attached to a table become methods on it`() {
        val src = """
            local helper = {}
            function helper.reset(amount)
            end
            helper.|
        """.trimIndent()
        val r = TestApi.completeAt(src, relicPath)
        val item = r.items.firstOrNull { it.label == "reset" }
        assertNotNull("expected helper.reset, got ${TestApi.labels(r)}", item)
        assertTrue(item!!.insertText.startsWith("reset("))
    }

    @Test
    fun `user declared classes provide their annotated fields`() {
        val src = """
            ---@class Loadout
            ---@field weapon string
            ---@field ammo integer

            ---@type Loadout
            local kit = {}
            kit.|
        """.trimIndent()
        val r = TestApi.completeAt(src, relicPath)
        val labels = TestApi.labels(r)
        assertTrue("expected annotated fields, got $labels", labels.containsAll(listOf("weapon", "ammo")))
        assertEquals("string", r.items.first { it.label == "weapon" }.detail)
    }

    @Test
    fun `loop variables take the element type of the array they walk`() {
        val src = """
            local names = { "a", "b" }
            for i, entry in ipairs(names) do
                entry.|
            end
        """.trimIndent()
        // The element type is `string`; nothing to offer, but resolution must not crash or leak.
        val r = TestApi.completeAt(src, relicPath)
        assertTrue(r.isMemberAccess)
    }

    // ---------------------------------------------------------------- separated globals

    @Test
    fun `a relic file is offered pickup and never potion`() {
        val labels = TestApi.labels(TestApi.completeAt("p|", relicPath))
        assertTrue("expected pickup, got $labels", "pickup" in labels)
        assertFalse("potion must not leak into an items file", "potion" in labels)
        assertFalse("progress must not leak into an items file", "progress" in labels)
    }

    @Test
    fun `an orb file is offered potion and never pickup`() {
        val labels = TestApi.labels(TestApi.completeAt("p|", orbPath))
        assertTrue("expected potion, got $labels", "potion" in labels)
        assertFalse("pickup must not leak into a potions file", "pickup" in labels)
    }

    @Test
    fun `the file's own global is ranked first`() {
        val r = TestApi.completeAt("pick|", relicPath)
        assertEquals("pickup", TestApi.labels(r).first())
    }

    @Test
    fun `nested ADVR event tables resolve to their callbacks`() {
        val labels = TestApi.labels(TestApi.completeAt("ADVR.WeaponComboEvents.|", "MyMod/weapons/blade.lua"))
        assertTrue("expected combo callbacks, got $labels", "onTriggerPressed" in labels)
        assertTrue("onWeaponComboSelected" in labels)
    }

    @Test
    fun `ADVR only offers the event table for this kind of file`() {
        val orb = TestApi.labels(TestApi.completeAt("ADVR.|", orbPath))
        assertTrue("expected PotionEvents, got $orb", "PotionEvents" in orb)
        assertFalse("WeaponComboEvents belongs to weapons/", "WeaponComboEvents" in orb)

        val weapon = TestApi.labels(TestApi.completeAt("ADVR.|", "MyMod/weapons/blade.lua"))
        assertTrue("WeaponComboEvents" in weapon)
        assertFalse("PotionEvents" in weapon)
    }

    @Test
    fun `globals defined in this file are offered, and only here`() {
        val src = """
            myCounter = 0
            function tick()
                myC|
            end
        """.trimIndent()
        val labels = TestApi.labels(TestApi.completeAt(src, relicPath))
        assertTrue("expected the file's own global, got $labels", "myCounter" in labels)

        // A different file, same mod: the global is gone, because ADVR does not share them.
        val other = TestApi.labels(TestApi.completeAt("myC|", "MyMod/items/other/other.lua"))
        assertFalse("globals must not cross files", "myCounter" in other)
    }

    @Test
    fun `required callbacks are offered as templates for the folder`() {
        val orb = TestApi.completeAt("ADVR.PotionEvents.onPot|", orbPath)
        assertTrue("onPotionBreak" in TestApi.labels(orb) || "onPotionRunOut" in TestApi.labels(orb))

        val bare = TestApi.completeAt("fun|", orbPath)
        val snippets = bare.items.filter { it.kind == com.advr.luaeditor.lua.CompletionKind.SNIPPET }
        assertTrue("expected templates in a potions file", snippets.isNotEmpty())
    }

    @Test
    fun `locals and parameters win over API globals`() {
        val src = """
            function ADVR.onLoad()
                local playerHealth = 10
                play|
            end
        """.trimIndent()
        val labels = TestApi.labels(TestApi.completeAt(src, relicPath))
        assertEquals("playerHealth", labels.first())
    }

    @Test
    fun `mod kind is read from the deepest matching folder`() {
        assertEquals(ModKind.RELIC, ModKind.forPath("MyMod/items/x/x.lua"))
        assertEquals(ModKind.POI, ModKind.forPath("MyMod/world/overgrown_dungeon/pois/a.lua"))
        assertEquals(ModKind.PROGRESS, ModKind.forPath("MyMod/progress_shops/acolyte/b.lua"))
        assertEquals(ModKind.UNKNOWN, ModKind.forPath("MyMod/scripts/util.lua"))
    }

    @Test
    fun `signature help reports the active parameter`() {
        val src = "pickup.CallFunctionIn(\"tick\", 1.0, "
        val ctx = TestApi.context(relicPath)
        val model = TestApi.model(src, relicPath)
        val sig = com.advr.luaeditor.lua.CompletionEngine(TestApi.index)
            .signatureHelp(src, src.length, model)
        assertNotNull("expected a signature for CallFunctionIn", sig)
        assertEquals(2, sig!!.activeParam)
        assertTrue(sig.params.isNotEmpty())
    }
}
