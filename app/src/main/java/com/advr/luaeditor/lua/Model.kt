package com.advr.luaeditor.lua

import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.ApiParam

enum class SymbolKind { LOCAL, PARAM, GLOBAL, FUNCTION, LOOP_VAR }

/**
 * A table shape discovered in the file being edited - a table literal, or a table grown field by
 * field through `t.foo = ...`. Referenced from [Symbol.type] as `@<id>`.
 */
class UserType(@JvmField val id: String, @JvmField var displayName: String) {
    @JvmField val members = LinkedHashMap<String, UserMember>()
    /** Set for `---@class Name` declared in user code. */
    @JvmField var declaredName: String = ""
    @JvmField var parent: String = ""
}

class UserMember(
    @JvmField val name: String,
    @JvmField var type: String,
    @JvmField var doc: String = "",
    @JvmField var callable: Boolean = false,
    @JvmField var params: List<ApiParam> = emptyList(),
    @JvmField val declOffset: Int = 0,
)

class Symbol(
    @JvmField val name: String,
    @JvmField var type: String,
    @JvmField val kind: SymbolKind,
    @JvmField val declOffset: Int,
    @JvmField val scopeStart: Int,
    @JvmField var scopeEnd: Int,
    @JvmField var doc: String = "",
    @JvmField var params: List<ApiParam> = emptyList(),
    @JvmField var ret: String = "",
) {
    fun visibleAt(offset: Int): Boolean = offset >= scopeStart && offset <= scopeEnd
}

/** A `function name(...)`, `function a.b(...)` or `local function name(...)` in the file. */
class LuaFunction(
    @JvmField val fullName: String,
    @JvmField val simpleName: String,
    @JvmField val ownerChain: List<String>,
    @JvmField val params: List<ApiParam>,
    @JvmField val declOffset: Int,
    @JvmField val nameOffset: Int,
    @JvmField val doc: String,
    @JvmField var ret: String = "",
    @JvmField val isLocal: Boolean = false,
)

class Reference(@JvmField val name: String, @JvmField val offset: Int, @JvmField val length: Int)

/** Everything the analyzer learned about one file. Rebuilt (debounced) on every edit. */
class FileModel(
    @JvmField val text: String,
    @JvmField val toks: List<Tok>,
    @JvmField val symbols: List<Symbol>,
    @JvmField val userTypes: Map<String, UserType>,
    /** `---@class Name` blocks written by the user, keyed by name. */
    @JvmField val userClasses: Map<String, UserType>,
    @JvmField val functions: List<LuaFunction>,
    /** Globals assigned in *this* file. ADVR keeps globals per file, so these never cross over. */
    @JvmField val globals: Map<String, Symbol>,
    @JvmField val callFunctionInRefs: List<Reference>,
    @JvmField val freeIdentifiers: List<Reference>,
) {
    @JvmField
    val definedFunctionNames: Set<String> = functions.mapTo(HashSet()) { it.fullName }

    fun symbolsVisibleAt(offset: Int): List<Symbol> =
        symbols.filter { it.visibleAt(offset) }

    fun lookup(name: String, offset: Int): Symbol? {
        var best: Symbol? = null
        for (s in symbols) {
            if (s.name != name || !s.visibleAt(offset)) continue
            // Innermost, most recent declaration wins, matching Lua's shadowing rules.
            if (best == null || s.scopeStart > best.scopeStart ||
                (s.scopeStart == best.scopeStart && s.declOffset > best.declOffset)
            ) {
                if (s.declOffset <= offset || s.kind == SymbolKind.FUNCTION || s.kind == SymbolKind.GLOBAL) best = s
            }
        }
        return best
    }

    /** Type lookups against this file's shapes plus the ADVR stubs. */
    fun resolver(api: ApiIndex) = TypeResolver(api, userTypes, userClasses) { name, offset ->
        lookup(name, offset)
    }

    companion object {
        fun empty(text: String = "") =
            FileModel(text, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList(), emptyList())
    }
}
