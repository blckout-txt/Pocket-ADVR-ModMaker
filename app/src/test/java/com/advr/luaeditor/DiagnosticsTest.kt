package com.advr.luaeditor

import com.advr.luaeditor.lua.LuaDiagnostics
import com.advr.luaeditor.lua.Severity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    private fun check(src: String, path: String) = LuaDiagnostics.run(
        src, TestApi.model(src, path), TestApi.context(path), TestApi.index,
    )

    @Test
    fun `an orb missing its callbacks is an error`() {
        val d = check("function ADVR.onLoad()\nend\n", "M/potions/fizz.lua")
        val messages = d.filter { it.severity == Severity.ERROR }.map { it.message }
        assertTrue("$messages", messages.any { it.contains("onPotionBreak") })
        assertTrue("$messages", messages.any { it.contains("onPotionRunOut") })
    }

    @Test
    fun `an orb with both callbacks is clean of that error`() {
        val src = """
            function ADVR.onLoad()
            end
            function ADVR.PotionEvents.onPotionBreak(a, b, c)
            end
            function ADVR.PotionEvents.onPotionRunOut()
            end
        """.trimIndent()
        val d = check(src, "M/potions/fizz.lua")
        assertFalse(d.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `a callback in the wrong folder is flagged`() {
        val src = """
            function ADVR.onLoad()
            end
            function ADVR.PotionEvents.onPotionBreak(a, b, c)
            end
        """.trimIndent()
        val d = check(src, "M/items/relic/relic.lua")
        assertTrue(d.any { it.message.contains("belongs to potions/") })
    }

    @Test
    fun `a per-file global from another kind of file is flagged`() {
        val src = "function ADVR.onLoad()\n\tpotion.name = \"x\"\nend\n"
        val d = check(src, "M/items/relic/relic.lua")
        assertTrue(
            d.map { it.message }.toString(),
            d.any { it.message.contains("`potion` only exists in potions/") },
        )
    }

    @Test
    fun `CallFunctionIn must target a function in the same file`() {
        val missing = check(
            "function ADVR.onLoad()\n\tpickup.CallFunctionIn(\"tick\", 1.0)\nend\n",
            "M/items/relic/relic.lua",
        )
        assertTrue(missing.any { it.message.contains("`tick` is not defined in this file") })

        val present = check(
            "function tick()\nend\nfunction ADVR.onLoad()\n\tpickup.CallFunctionIn(\"tick\", 1.0)\nend\n",
            "M/items/relic/relic.lua",
        )
        assertFalse(present.any { it.message.contains("`tick` is not defined") })
    }

    @Test
    fun `a typo on the file's own global is flagged`() {
        val d = check("function ADVR.onLoad()\n\tpickup.nmae = \"x\"\nend\n", "M/items/relic/relic.lua")
        assertTrue(d.any { it.message.contains("no field `nmae`") && it.message.contains("did you mean `name`") })
    }

    @Test
    fun `real stub fields are never flagged`() {
        val src = """
            function ADVR.onLoad()
                pickup.name = "Thorn"
                pickup.desc = "Ouch"
                pickup.weight = 100.0
                pickup.maxAmount = 1
                pickup.price = 50
                pickup.tier = 1
                pickup.spawnsIn = {}
                pickup.supportedInMultiplayer = true
            end

            function ADVR.onPickup()
                pickup.RegisterItem()
            end
        """.trimIndent()
        val d = check(src, "M/items/relic/relic.lua")
        assertTrue("unexpected: ${d.map { it.message }}", d.isEmpty())
    }

    @Test
    fun `a missing end is reported`() {
        val d = check("function ADVR.onLoad()\n\tlocal a = 1\n", "M/items/relic/relic.lua")
        assertTrue(d.any { it.message.contains("missing 1 `end`") })
    }

    @Test
    fun `locals and file functions are not treated as undefined`() {
        val src = """
            local function helper(value)
                return value + 1
            end

            function ADVR.onLoad()
                local total = helper(2)
                pickup.price = total
            end
        """.trimIndent()
        val d = check(src, "M/items/relic/relic.lua")
        assertTrue("unexpected: ${d.map { it.message }}", d.none { it.message.contains("not defined in this file") })
    }
}
