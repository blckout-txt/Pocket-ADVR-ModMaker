package com.advr.luaeditor.lua

import com.advr.luaeditor.api.ApiIndex
import com.advr.luaeditor.api.ApiParam
import com.advr.luaeditor.api.TypeNames

enum class MemberKind { FIELD, METHOD, EVENT }

class MemberInfo(
    @JvmField val name: String,
    @JvmField val type: String,
    @JvmField val doc: String,
    @JvmField val kind: MemberKind,
    @JvmField val params: List<ApiParam>,
    @JvmField val ret: String,
    @JvmField val owner: String,
    @JvmField val overloads: Int = 1,
    @JvmField val fromUserCode: Boolean = false,
)

/**
 * One place that answers "what type is this?" and "what members does that type have?", shared by the
 * analyzer (while it builds the model) and by completion (while the user types).
 *
 * User table shapes take precedence over the API stubs so a custom object stays accurate the moment
 * a new field is assigned to it.
 */
class TypeResolver(
    private val api: ApiIndex,
    private val userTypes: Map<String, UserType>,
    private val userClasses: Map<String, UserType>,
    private val lookupSymbol: (String, Int) -> Symbol?,
) {

    fun typeOfName(name: String, offset: Int): String {
        lookupSymbol(name, offset)?.let { if (it.type.isNotEmpty()) return it.type }
        api.globalType(name)?.let { return it }
        if (userClasses.containsKey(name)) return name
        return ""
    }

    fun memberType(type: String, member: String): String = memberType(type, member, 0)

    private fun memberType(type: String, member: String, depth: Int): String {
        if (type.isEmpty() || depth > MAX_DEPTH) return ""
        if (type.startsWith("@")) return userTypes[type]?.members?.get(member)?.type ?: ""
        userClasses[type]?.let { uc ->
            uc.members[member]?.let { return it.type }
            return if (uc.parent.isNotEmpty()) memberType(uc.parent, member, depth + 1) else ""
        }
        api.findField(type, member)?.let { f -> return if (f.isCallable) "function" else f.type }
        if (api.findMethods(type, member).isNotEmpty()) return "function"
        return ""
    }

    fun memberCallReturn(type: String, member: String, argc: Int): String =
        memberCallReturn(type, member, argc, 0)

    private fun memberCallReturn(type: String, member: String, argc: Int, depth: Int): String {
        if (type.isEmpty() || depth > MAX_DEPTH) return ""
        if (type.startsWith("@")) {
            val m = userTypes[type]?.members?.get(member) ?: return ""
            return if (m.type == "function") m.ret() else ""
        }
        userClasses[type]?.let { uc ->
            uc.members[member]?.let { return TypeNames.functionReturn(it.type) }
            return if (uc.parent.isNotEmpty()) memberCallReturn(uc.parent, member, argc, depth + 1) else ""
        }
        val overloads = api.findMethods(type, member)
        if (overloads.isNotEmpty()) {
            return (overloads.firstOrNull { it.params.size == argc } ?: overloads[0]).ret
        }
        api.findField(type, member)?.let { f -> if (f.isCallable) return TypeNames.functionReturn(f.type) }
        return ""
    }

    fun elementOf(type: String): String {
        TypeNames.element(type).let { if (it.isNotEmpty()) return it }
        if (type.startsWith("@")) {
            val dn = userTypes[type]?.displayName ?: return ""
            if (dn.endsWith("[]")) return dn.dropLast(2)
        }
        return ""
    }

    /** Human readable name for a type, including the synthesised `@n` table shapes. */
    fun display(type: String): String = when {
        type.isEmpty() -> "any"
        type.startsWith("@") -> {
            val ut = userTypes[type]
            val count = ut?.members?.size ?: 0
            if (ut == null) "table" else "table{${ut.members.keys.take(3).joinToString(", ")}" +
                (if (count > 3) ", …}" else "}")
        }
        else -> TypeNames.display(type)
    }

    /** Everything reachable through a `.` on a value of [type], nearest declaration first. */
    fun membersOf(type: String): List<MemberInfo> = membersOf(type, 0)

    private fun membersOf(type: String, depth: Int): List<MemberInfo> {
        if (type.isEmpty() || depth > MAX_DEPTH) return emptyList()
        val out = LinkedHashMap<String, MemberInfo>()

        if (type.startsWith("@")) {
            val ut = userTypes[type] ?: return emptyList()
            for (m in ut.members.values) {
                out[m.name] = MemberInfo(
                    m.name, m.type, m.doc,
                    if (m.callable || m.type == "function") MemberKind.METHOD else MemberKind.FIELD,
                    m.params, m.ret(), ut.displayName, fromUserCode = true,
                )
            }
            return out.values.toList()
        }

        userClasses[type]?.let { uc ->
            for (m in uc.members.values) {
                out[m.name] = MemberInfo(
                    m.name, m.type, m.doc,
                    if (TypeNames.isFunction(m.type) || m.callable) MemberKind.METHOD else MemberKind.FIELD,
                    if (m.params.isEmpty()) TypeNames.functionParams(m.type) else m.params,
                    TypeNames.functionReturn(m.type), uc.declaredName, fromUserCode = true,
                )
            }
            if (uc.parent.isNotEmpty()) {
                for (m in membersOf(uc.parent, depth + 1)) out.putIfAbsent(m.name, m)
            }
            return out.values.toList()
        }

        for (f in api.fieldsOf(type)) {
            out.putIfAbsent(
                f.name,
                MemberInfo(
                    f.name, f.type, f.doc,
                    if (f.isCallable) MemberKind.EVENT else MemberKind.FIELD,
                    TypeNames.functionParams(f.type), TypeNames.functionReturn(f.type), f.owner,
                )
            )
        }
        for (group in api.methodsOf(type)) {
            val first = group.firstOrNull() ?: continue
            out.putIfAbsent(
                first.name,
                MemberInfo(
                    first.name, "function", first.doc, MemberKind.METHOD,
                    first.params, first.ret, first.owner, overloads = group.size,
                )
            )
        }
        return out.values.toList()
    }

    /** All overloads for a member, used by signature help. */
    fun signaturesOf(type: String, member: String): List<MemberInfo> = signaturesOf(type, member, 0)

    private fun signaturesOf(type: String, member: String, depth: Int): List<MemberInfo> {
        if (depth > MAX_DEPTH) return emptyList()
        if (type.startsWith("@")) {
            val m = userTypes[type]?.members?.get(member) ?: return emptyList()
            return listOf(MemberInfo(m.name, m.type, m.doc, MemberKind.METHOD, m.params, m.ret(), type, fromUserCode = true))
        }
        userClasses[type]?.let { uc ->
            val m = uc.members[member]
                ?: return if (uc.parent.isNotEmpty()) signaturesOf(uc.parent, member, depth + 1) else emptyList()
            return listOf(
                MemberInfo(m.name, m.type, m.doc, MemberKind.METHOD,
                    TypeNames.functionParams(m.type), TypeNames.functionReturn(m.type), type, fromUserCode = true)
            )
        }
        val methods = api.findMethods(type, member)
        if (methods.isNotEmpty()) {
            return methods.map { MemberInfo(it.name, "function", it.doc, MemberKind.METHOD, it.params, it.ret, it.owner) }
        }
        api.findField(type, member)?.let { f ->
            if (f.isCallable) {
                return listOf(
                    MemberInfo(f.name, f.type, f.doc, MemberKind.EVENT,
                        TypeNames.functionParams(f.type), TypeNames.functionReturn(f.type), f.owner)
                )
            }
        }
        return emptyList()
    }
}

private const val MAX_DEPTH = 12

/** A user member typed `function` carries its return type on the declaring [LuaFunction]. */
private fun UserMember.ret(): String = if (TypeNames.isFunction(type)) TypeNames.functionReturn(type) else ""
