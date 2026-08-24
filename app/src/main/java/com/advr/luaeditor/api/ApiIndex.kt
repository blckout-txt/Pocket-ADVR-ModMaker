package com.advr.luaeditor.api

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The generated ADVR API surface, loaded from `assets/advr_api.txt.gz`.
 *
 * The asset is produced by `tools/gen_api_index.py` out of the LuaLS definition stubs that ship
 * with the ADVR Modding Tools VS Code extension, so everything the desktop tooling knows about is
 * available here without a language server.
 */
class ApiParam(@JvmField val name: String, @JvmField val type: String) {
    override fun toString() = "$name: ${TypeNames.display(type)}"
}

class ApiMethod(
    @JvmField val owner: String,
    @JvmField val name: String,
    @JvmField val params: List<ApiParam>,
    @JvmField val ret: String,
    @JvmField val doc: String,
) {
    fun signature(): String =
        "$name(" + params.joinToString(", ") { it.toString() } + ")" +
            if (ret.isNotEmpty() && ret != "any") " -> ${TypeNames.display(ret)}" else ""
}

class ApiField(
    @JvmField val owner: String,
    @JvmField val name: String,
    @JvmField val type: String,
    @JvmField val doc: String,
) {
    /** Stub events are declared as fields typed `fun(a: X):R`; treat those as callable. */
    val isCallable: Boolean get() = type.startsWith("fun(")
}

class ApiClass(@JvmField val name: String, @JvmField val parent: String, @JvmField val doc: String) {
    @JvmField val fields = LinkedHashMap<String, ApiField>()
    @JvmField val methods = LinkedHashMap<String, MutableList<ApiMethod>>()
}

class ApiIndex(
    @JvmField val version: String,
    @JvmField val classes: Map<String, ApiClass>,
    /** Global name (may be dotted, e.g. `ADVR.PotionEvents`) to the class it is typed as. */
    @JvmField val globals: Map<String, String>,
) {
    /** Top level globals only - `ADVR`, not `ADVR.PotionEvents`. */
    @JvmField
    val rootGlobals: Map<String, String> = globals.filterKeys { !it.contains('.') }

    fun classOf(typeName: String): ApiClass? {
        if (typeName.isEmpty()) return null
        return classes[TypeNames.primary(typeName)]
    }

    fun globalType(name: String): String? = globals[name]

    /** Walks the `@class X : Y` chain, nearest declaration wins. */
    fun fieldsOf(typeName: String): List<ApiField> {
        val out = LinkedHashMap<String, ApiField>()
        forEachInHierarchy(typeName) { k -> k.fields.forEach { (n, f) -> out.putIfAbsent(n, f) } }
        return out.values.toList()
    }

    fun methodsOf(typeName: String): List<List<ApiMethod>> {
        val out = LinkedHashMap<String, List<ApiMethod>>()
        forEachInHierarchy(typeName) { k -> k.methods.forEach { (n, m) -> out.putIfAbsent(n, m) } }
        return out.values.toList()
    }

    fun findField(typeName: String, member: String): ApiField? {
        var found: ApiField? = null
        forEachInHierarchy(typeName) { k -> if (found == null) found = k.fields[member] }
        return found
    }

    fun findMethods(typeName: String, member: String): List<ApiMethod> {
        var found: List<ApiMethod>? = null
        forEachInHierarchy(typeName) { k -> if (found == null) found = k.methods[member] }
        return found ?: emptyList()
    }

    private inline fun forEachInHierarchy(typeName: String, action: (ApiClass) -> Unit) {
        var cur = classOf(typeName)
        var guard = 0
        while (cur != null && guard++ < 16) {
            action(cur)
            val p = cur.parent
            cur = if (p.isEmpty()) null else classes[TypeNames.primary(p)]
        }
    }

    companion object {
        const val ASSET = "advr_api.txt"

        val EMPTY = ApiIndex("", emptyMap(), emptyMap())

        /**
         * The asset is plain text; the APK stores it deflated, so this is a single decompression
         * pass. Call it off the main thread - it is a few megabytes.
         */
        fun load(context: Context): ApiIndex = load(
            BufferedReader(InputStreamReader(context.assets.open(ASSET), Charsets.UTF_8), 64 * 1024)
        )

        fun load(reader: BufferedReader): ApiIndex {
            val classes = LinkedHashMap<String, ApiClass>(512)
            val globals = LinkedHashMap<String, String>(512)
            var version = ""

            reader.use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (line.length >= 2 && line[0] != '#') {
                        when (line[0]) {
                            'C' -> {
                                val p = split(line, 4)
                                classes[p[1]] = ApiClass(p[1], p[2], restore(p[3]))
                            }
                            'F' -> {
                                val p = split(line, 5)
                                classes[p[1]]?.let { k ->
                                    k.fields[p[2]] = ApiField(p[1], p[2], p[3], restore(p[4]))
                                }
                            }
                            'M' -> {
                                val p = split(line, 6)
                                classes[p[1]]?.let { k ->
                                    val params = if (p[3].isEmpty()) emptyList() else
                                        p[3].split(',').mapNotNull { seg ->
                                            val i = seg.lastIndexOf(':')
                                            if (i <= 0) null else ApiParam(seg.substring(0, i), seg.substring(i + 1))
                                        }
                                    k.methods.getOrPut(p[2]) { ArrayList(2) }
                                        .add(ApiMethod(p[1], p[2], params, p[4], restore(p[5])))
                                }
                            }
                            'G' -> {
                                val p = split(line, 3)
                                globals[p[1]] = p[2]
                            }
                            'V' -> version = line.substring(2)
                        }
                    }
                    line = r.readLine()
                }
            }
            linkDottedGlobals(classes, globals)
            return ApiIndex(version, classes, globals)
        }

        /**
         * The stubs declare `ADVR.WeaponComboEvents` as its own global rather than as a field of
         * `ADVREvents`, so nothing would connect the two. Hang each dotted global off its parent's
         * class as a field, which is what a reader of `ADVR.` expects to find there.
         */
        private fun linkDottedGlobals(
            classes: MutableMap<String, ApiClass>,
            globals: Map<String, String>,
        ) {
            for ((name, type) in globals) {
                val dot = name.lastIndexOf('.')
                if (dot <= 0) continue
                val parentGlobal = name.substring(0, dot)
                val member = name.substring(dot + 1)
                val parentClass = classes[globals[parentGlobal] ?: continue] ?: continue
                parentClass.fields.putIfAbsent(
                    member,
                    ApiField(parentClass.name, member, type, classes[type]?.doc ?: ""),
                )
            }
        }

        /** Split into exactly [count] fields; the generator guarantees the arity per record type. */
        private fun split(line: String, count: Int): Array<String> {
            val out = Array(count) { "" }
            var idx = 0
            var start = 0
            while (idx < count - 1) {
                val i = line.indexOf('|', start)
                if (i < 0) break
                out[idx++] = line.substring(start, i)
                start = i + 1
            }
            out[idx] = line.substring(start)
            return out
        }

        /** The generator escapes literal `|` inside values as U+2502 so records stay splittable. */
        private fun restore(s: String) = if (s.indexOf('│') >= 0) s.replace('│', '|') else s
    }
}

/** Helpers for the LuaLS type strings used by the stubs (`string|nil`, `GameObject[]`, `fun(..):R`). */
object TypeNames {

    /** First non-nil branch of a union, with array/optional markers stripped. */
    fun primary(type: String): String {
        var t = type.trim()
        if (t.isEmpty()) return t
        if (t.startsWith("fun(")) return "function"
        val bar = t.indexOf('│').let { if (it >= 0) it else t.indexOf('|') }
        if (bar >= 0) {
            val head = t.substring(0, bar).trim()
            val tail = t.substring(bar + 1).trim()
            t = if (head.isNotEmpty() && head != "nil") head else tail
            // A union of more than two branches: recurse over what is left.
            if (t.contains('|') || t.contains('│')) return primary(t)
        }
        while (t.endsWith("[]")) t = t.dropLast(2)
        if (t.endsWith("?")) t = t.dropLast(1)
        return t.trim()
    }

    fun isArray(type: String): Boolean {
        val t = unionHead(type)
        return t.endsWith("[]")
    }

    /** Element type of `X[]`, or empty when [type] is not an array. */
    fun element(type: String): String {
        val t = unionHead(type)
        return if (t.endsWith("[]")) t.dropLast(2).trim() else ""
    }

    fun isFunction(type: String): Boolean = type.trimStart().startsWith("fun(")

    /** Return type declared by a `fun(...):R` field, or "any". */
    fun functionReturn(type: String): String {
        val t = type.trim()
        if (!t.startsWith("fun(")) return "any"
        var depth = 0
        var i = 3
        while (i < t.length) {
            when (t[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        val rest = t.substring(i + 1).trim()
                        return if (rest.startsWith(":")) rest.substring(1).trim() else "any"
                    }
                }
            }
            i++
        }
        return "any"
    }

    /** Parameters declared by a `fun(a: X, b: Y):R` field. */
    fun functionParams(type: String): List<ApiParam> {
        val t = type.trim()
        if (!t.startsWith("fun(")) return emptyList()
        val close = matchingParen(t, 3)
        if (close < 0) return emptyList()
        val inner = t.substring(4, close).trim()
        if (inner.isEmpty()) return emptyList()
        return splitTopLevel(inner).mapNotNull { seg ->
            val i = seg.indexOf(':')
            if (i < 0) ApiParam(seg.trim(), "any")
            else ApiParam(seg.substring(0, i).trim(), seg.substring(i + 1).trim())
        }
    }

    fun display(type: String): String =
        if (type.indexOf('│') >= 0) type.replace('│', '|') else type

    private fun unionHead(type: String): String {
        val t = type.trim()
        val bar = t.indexOf('│').let { if (it >= 0) it else t.indexOf('|') }
        if (bar < 0) return t
        val head = t.substring(0, bar).trim()
        return if (head.isNotEmpty() && head != "nil") head else unionHead(t.substring(bar + 1))
    }

    private fun matchingParen(s: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>(4)
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(', '<', '{' -> depth++
                ')', '>', '}' -> depth--
                ',' -> if (depth == 0) { out.add(s.substring(start, i)); start = i + 1 }
            }
        }
        out.add(s.substring(start))
        return out.filter { it.isNotBlank() }
    }
}
