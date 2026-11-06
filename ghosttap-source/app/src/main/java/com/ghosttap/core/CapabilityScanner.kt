package com.ghosttap.core

import java.lang.reflect.Modifier

/**
 * What the scanner found when it looked at the real code.
 *
 * @param registered "ClassName.methodName" strings whose sourceMethod appears
 *   in the registry — the wiring the engine already knows about.
 * @param candidates public methods that exist on the ViewModels but have NO
 *   registry entry — real code that isn't visible to the intelligence system
 *   yet. Candidates are leads for wiring, not promises: a method may be UI
 *   glue, a helper, or genuinely new capability surface.
 */
data class ScanReport(
    val registered: List<String>,
    val candidates: List<String>
)

/**
 * Genuine introspection over Ghost Tap's actual ViewModel code — no new
 * dependencies, just java.lang.reflect.
 *
 * For each ViewModel instance it lists public declared methods (name +
 * parameter types), normalizes Kotlin property accessors (getFoo -> foo),
 * and compares against every registry entry's [IntelCapability.sourceMethod]
 * ("ClassName.methodName"). Anything public with no registry entry is
 * reported as a candidate. This is how the system discovers capabilities
 * from the real implementation rather than a fixed list.
 */
object CapabilityScanner {

    private val skipNames = setOf(
        "toString", "hashCode", "equals", "getClass",
        "notify", "notifyAll", "wait", "clone", "finalize"
    )

    fun scan(viewModels: List<Any>, registry: CapabilityRegistry): ScanReport {
        val knownSources = registry.all()
            .map { it.sourceMethod.substringAfterLast('.').substringBefore('(').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Manual") && it != "IntelViewModel (manual instruction)" }
            .toSet()
        // Map normalized method name -> owning class names that declare it.
        val declared = mutableMapOf<String, MutableSet<String>>()
        // Map "Class.method" -> signature for display.
        val signatures = mutableMapOf<String, String>()

        for (vm in viewModels) {
            val cls = vm.javaClass
            val clsName = cls.simpleName.ifBlank { cls.name.substringAfterLast('.') }
            for (m in cls.declaredMethods) {
                try {
                    if (m.isSynthetic || m.isBridge) continue
                    if (!Modifier.isPublic(m.modifiers)) continue
                    val raw = m.name
                    if (raw in skipNames) continue
                    if ('$' in raw) continue
                    if (raw.startsWith("component")) continue
                    if (raw == "copy") continue
                    val norm = normalize(raw) ?: continue
                    val sig = "$clsName.$raw(" +
                        m.parameterTypes.joinToString(", ") { it.simpleName } + ")"
                    declared.getOrPut(norm) { mutableSetOf() }.add(clsName)
                    signatures["$clsName.$raw"] = sig
                } catch (_: Exception) {
                    // One bad method must never break the scan.
                }
            }
        }

        val registered = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        // For each declared method, check whether any registry sourceMethod
        // names it (comparing normalized names to tolerate getFoo/foo style).
        val seen = mutableSetOf<String>()
        for ((norm, owners) in declared) {
            for (owner in owners) {
                val key = "$owner.$norm"
                if (!seen.add(key)) continue
                val sigKey = signatures.keys.firstOrNull {
                    it.substringBefore('.') == owner &&
                        (normalize(it.substringAfter('.').substringBefore('(')) == norm)
                }
                val display = sigKey?.let { signatures[it] } ?: "$owner.$norm()"
                if (norm in knownSources || knownSources.any { src ->
                        src.substringAfterLast('.') == norm ||
                            normalize(src.substringAfterLast('.')) == norm
                    }) {
                    registered.add(display)
                } else {
                    candidates.add(display)
                }
            }
        }
        return ScanReport(
            registered = registered.sorted(),
            candidates = candidates.sorted()
        )
    }

    /**
     * Kotlin `val foo` compiles to `getFoo()`/`isFoo()`; normalize both to
     * "foo" so property-style sourceMethods ("GhostTapViewModel.allTargets")
     * match their accessors. Returns null for names we should ignore.
     */
    private fun normalize(raw: String): String? {
        if (raw.startsWith("get") && raw.length > 3 && raw[3].isUpperCase()) {
            return raw.substring(3).replaceFirstChar { it.lowercase() }
        }
        if (raw.startsWith("is") && raw.length > 2 && raw[2].isUpperCase()) {
            return raw.substring(2).replaceFirstChar { it.lowercase() }
        }
        if (raw.startsWith("set") && raw.length > 3 && raw[3].isUpperCase()) {
            return null // setters are not capability surface
        }
        return raw
    }
}
