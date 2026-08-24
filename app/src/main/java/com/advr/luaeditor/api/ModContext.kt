package com.advr.luaeditor.api

/**
 * ADVR runs every mod Lua file in its own environment, so globals do not leak between files and the
 * set of globals a file starts with depends on where that file sits inside the mod.
 *
 * A file under `items/` is handed a `pickup`; a file under `potions/` is handed a `potion` and is
 * expected to define the PotionEvents callbacks. [ModKind] captures that mapping so completion,
 * diagnostics and templates can all agree on what this particular file is allowed to see.
 */
enum class ModKind(
    val label: String,
    /** Folder name anywhere on the path that selects this kind. */
    val folder: String,
    /** The per-file global the game injects, or empty when the kind has none. */
    val selfGlobal: String,
    /** `ADVR.<table>` holding this kind's callbacks, or empty. */
    val eventTable: String,
    val requiredFunctions: List<String>,
) {
    RELIC("Relic", "items", "pickup", "RelicEvents", emptyList()),
    CHALLENGE("Challenge", "challenges", "challenge", "", emptyList()),
    ORB("Orb", "potions", "potion", "PotionEvents", listOf(
        "ADVR.PotionEvents.onPotionBreak",
        "ADVR.PotionEvents.onPotionRunOut",
    )),
    PROGRESS("Progress shop", "progress_shops", "progress", "ProgressEvents", listOf(
        "ADVR.ProgressEvents.onBuy",
    )),
    ACHIEVEMENT("Achievement", "achievements", "achievement", "", emptyList()),
    SPEECHBUBBLE("Speech bubble", "speechbubbles", "bubble", "SpeechbubbleEvents", listOf(
        "ADVR.SpeechbubbleEvents.onSpeechbubbleOpen",
        "ADVR.SpeechbubbleEvents.onDialogFinished",
    )),
    RUN_MODIFIER("Run modifier", "run_modifiers", "modifier", "ModifierEvents", listOf(
        "ADVR.ModifierEvents.getFinalValue",
    )),
    POI("Point of interest", "pois", "poi", "POIEvents", listOf(
        "ADVR.POIEvents.onFound",
    )),
    WEAPON("Weapon combo", "weapons", "combo", "WeaponComboEvents", listOf(
        "ADVR.WeaponComboEvents.onWeaponComboSelected",
        "ADVR.WeaponComboEvents.onTriggerPressed",
        "ADVR.WeaponComboEvents.onTriggerReleased",
        "ADVR.WeaponComboEvents.onRunStart",
        "ADVR.WeaponComboEvents.isUnlocked",
    )),
    JOURNAL("Journal page", "journalpages", "journal", "JournalEvents", listOf(
        "ADVR.JournalEvents.onPageCollected",
    )),
    UNKNOWN("Lua", "", "", "", emptyList());

    companion object {
        /** Every per-file global, so the ones this file does *not* get can be hidden. */
        val allSelfGlobals: Set<String> =
            entries.mapNotNull { it.selfGlobal.ifEmpty { null } }.toSet()

        val allEventTables: Set<String> =
            entries.mapNotNull { it.eventTable.ifEmpty { null } }.toSet()

        private val byFolder: Map<String, ModKind> =
            entries.filter { it.folder.isNotEmpty() }.associateBy { it.folder }

        /**
         * Resolves the kind from a workspace-relative path. The deepest matching folder wins so a
         * file at `world/overgrown_dungeon/pois/x.lua` still resolves as a POI.
         */
        fun forPath(path: String): ModKind {
            val parts = path.split('/', '\\').filter { it.isNotEmpty() }
            for (i in parts.indices.reversed()) {
                byFolder[parts[i].lowercase()]?.let { return it }
            }
            return UNKNOWN
        }
    }
}

/** Everything the analyzer and completion engine need to know about the file being edited. */
class FileContext(
    val path: String,
    val fileName: String,
    val kind: ModKind,
) {
    val baseName: String = fileName.removeSuffix(".lua")

    /** True when [global] is a per-file global belonging to a *different* kind of file. */
    fun isForeignSelfGlobal(global: String): Boolean {
        if (kind == ModKind.UNKNOWN) return false
        return global in ModKind.allSelfGlobals && global != kind.selfGlobal
    }

    /** True when the `ADVR.<table>` callbacks belong to a different kind of file. */
    fun isForeignEventTable(table: String): Boolean {
        if (kind == ModKind.UNKNOWN) return false
        return table in ModKind.allEventTables && table != kind.eventTable
    }

    companion object {
        fun of(relativePath: String, fileName: String) =
            FileContext(relativePath, fileName, ModKind.forPath(relativePath))
    }
}
