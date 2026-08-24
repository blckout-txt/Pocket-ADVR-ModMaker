package com.advr.luaeditor.lua

import com.advr.luaeditor.api.FileContext
import com.advr.luaeditor.api.ModKind

class Snippet(
    @JvmField val label: String,
    @JvmField val detail: String,
    @JvmField val doc: String,
    /** `$0` marks the caret. */
    @JvmField val body: String,
    @JvmField val bonus: Int,
)

/**
 * Templates. The callbacks ADVR *requires* for the folder a file sits in are offered first, so a new
 * orb or relic can be filled in without remembering which events it owes the game.
 */
object Snippets {

    private val generic = listOf(
        Snippet("function", "function name(...) end", "", "function $0()\n\t\nend", 60),
        Snippet("local function", "local function name(...) end", "", "local function $0()\n\t\nend", 58),
        Snippet("if", "if ... then ... end", "", "if $0 then\n\t\nend", 56),
        Snippet("ifelse", "if ... then ... else ... end", "", "if $0 then\n\t\nelse\n\t\nend", 54),
        Snippet("for i", "numeric for loop", "", "for i = 1, $0 do\n\t\nend", 54),
        Snippet("for ipairs", "array loop", "", "for i, v in ipairs($0) do\n\t\nend", 54),
        Snippet("for pairs", "table loop", "", "for k, v in pairs($0) do\n\t\nend", 52),
        Snippet("while", "while ... do ... end", "", "while $0 do\n\t\nend", 50),
        Snippet("repeat", "repeat ... until", "", "repeat\n\t$0\nuntil ", 48),
        Snippet("log", "logging.Log(...)", "", "logging.Log($0)", 62),
    )

    fun forContext(ctx: FileContext): List<Snippet> {
        val out = ArrayList<Snippet>(generic.size + 8)
        val kind = ctx.kind

        for (fn in kind.requiredFunctions) {
            out.add(
                Snippet(
                    label = fn,
                    detail = "required by ${kind.folder}/",
                    doc = "ADVR expects every ${kind.label.lowercase()} to define this callback.",
                    body = "function $fn()\n\t$0\nend",
                    bonus = 195,
                )
            )
        }

        if (kind != ModKind.UNKNOWN) {
            out.add(
                Snippet(
                    "ADVR.onLoad", "called once when the mod loads",
                    "Set up the ${kind.label.lowercase()}'s fields here.",
                    "function ADVR.onLoad()\n\t$0\nend", 190,
                )
            )
        }

        when (kind) {
            ModKind.RELIC -> {
                out.add(Snippet("ADVR.onPickup", "called when the relic is picked up", "",
                    "function ADVR.onPickup()\n\tpickup.RegisterItem()$0\nend", 188))
                out.add(Snippet("relic fields", "fill in pickup.*", "",
                    RELIC_FIELDS, 186))
            }
            ModKind.ORB -> out.add(Snippet("orb fields", "fill in potion.*", "", ORB_FIELDS, 186))
            ModKind.PROGRESS -> out.add(Snippet("progress fields", "fill in progress.*", "", PROGRESS_FIELDS, 186))
            ModKind.ACHIEVEMENT -> out.add(Snippet("achievement fields", "fill in achievement.*", "", ACHIEVEMENT_FIELDS, 186))
            else -> Unit
        }

        out.addAll(generic)
        return out
    }

    /** Contents for a brand new file created inside a mod folder. */
    fun fileTemplate(kind: ModKind, name: String): String = when (kind) {
        ModKind.RELIC ->
            "function ADVR.onLoad()\n" +
                "\tpickup.name = \"$name\"\n\tpickup.desc = \"TODO\"\n\tpickup.weight = 100.0\n" +
                "\tpickup.maxAmount = 1\n\tpickup.amountUses = 1\n\tpickup.price = 50\n\tpickup.tier = 1\n" +
                "\tpickup.spawnsIn = {}\n\tpickup.supportedInMultiplayer = true\nend\n\n" +
                "function ADVR.onPickup()\n\tpickup.RegisterItem()\nend\n"
        ModKind.CHALLENGE -> "function ADVR.onLoad()\n\t\nend\n"
        ModKind.ORB ->
            "function ADVR.onLoad()\n" +
                "\tpotion.name = \"$name\"\n\tpotion.desc = \"TODO\"\n\tpotion.weight = 100.0\n" +
                "\tpotion.effectTime = 30\n\tpotion.price = 50\n\tpotion.spawnsIn = {}\n" +
                "\tpotion.color = colors.Create(201.0/255.0, 300.0/255.0, 125.0/255.0, 1)\n" +
                "\tpotion.createEffectInstance = false\n\tpotion.supportedInMultiplayer = true\nend\n\n" +
                "function ADVR.PotionEvents.onPotionBreak(tmpPotion, tmpEffectInstance, stateAuthority)\n\t\nend\n\n" +
                "function ADVR.PotionEvents.onPotionRunOut()\n\t\nend\n"
        ModKind.PROGRESS ->
            "function ADVR.onLoad()\n\tprogress.name = \"$name\"\n\tprogress.desc = \"TODO\"\n" +
                "\tprogress.price = 50\n\tprogress.predecessor = \"TODO\"\nend\n\n" +
                "function ADVR.ProgressEvents.onBuy()\n\t\nend\n"
        ModKind.ACHIEVEMENT ->
            "function ADVR.onLoad()\n\tachievement.name = \"$name\"\n\tachievement.desc = \"TODO\"\n" +
                "\tachievement.hideDescription = true\n\tachievement.insightReward = 5\nend\n"
        ModKind.SPEECHBUBBLE ->
            "function ADVR.onLoad()\n\t\nend\n\n" +
                "function ADVR.SpeechbubbleEvents.onSpeechbubbleOpen()\n\t\nend\n\n" +
                "function ADVR.SpeechbubbleEvents.onDialogFinished()\n\t\nend\n"
        ModKind.RUN_MODIFIER ->
            "function ADVR.onLoad()\n\tmodifier.name = \"$name\"\n\tmodifier.desc = \"TODO\"\nend\n\n" +
                "function ADVR.ModifierEvents.getFinalValue()\n\treturn 1.0\nend\n"
        ModKind.POI ->
            "function ADVR.onLoad()\n\t\nend\n\nfunction ADVR.POIEvents.onFound(firstVisit)\n\t\nend\n"
        ModKind.WEAPON ->
            "function ADVR.onLoad()\n\tcombo.name = \"$name\"\nend\n\n" +
                "function ADVR.WeaponComboEvents.onWeaponComboSelected()\n\t\nend\n\n" +
                "function ADVR.WeaponComboEvents.onTriggerPressed(weaponBase, hand, isEmpty)\n\t\nend\n\n" +
                "function ADVR.WeaponComboEvents.onTriggerReleased(weaponBase, hand)\n\t\nend\n\n" +
                "function ADVR.WeaponComboEvents.onRunStart()\n\t\nend\n\n" +
                "function ADVR.WeaponComboEvents.isUnlocked()\n\treturn true\nend\n"
        ModKind.JOURNAL ->
            "function ADVR.onLoad()\n\t\nend\n\nfunction ADVR.JournalEvents.onPageCollected()\n\t\nend\n"
        ModKind.UNKNOWN -> ""
    }

    private const val RELIC_FIELDS =
        "pickup.name = \"$0\"\npickup.desc = \"TODO\"\npickup.weight = 100.0\npickup.maxAmount = 1\n" +
            "pickup.amountUses = 1\npickup.price = 50\npickup.tier = 1\npickup.spawnsIn = {}\n" +
            "pickup.supportedInMultiplayer = true"

    private const val ORB_FIELDS =
        "potion.name = \"$0\"\npotion.desc = \"TODO\"\npotion.weight = 100.0\npotion.effectTime = 30\n" +
            "potion.price = 50\npotion.spawnsIn = {}\npotion.supportedInMultiplayer = true"

    private const val PROGRESS_FIELDS =
        "progress.name = \"$0\"\nprogress.desc = \"TODO\"\nprogress.price = 50\nprogress.predecessor = \"TODO\""

    private const val ACHIEVEMENT_FIELDS =
        "achievement.name = \"$0\"\nachievement.desc = \"TODO\"\nachievement.hideDescription = true\n" +
            "achievement.insightReward = 5"
}
