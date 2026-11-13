package com.lucca.ko.domain

/** Pure semver-ish comparison for "is the release newer than what's installed". */
object VersionCompare {

    /** Parses "v0.14.0", "0.14", "1.2.3-beta" into numeric parts; null if no leading number. */
    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }

    /** Negative if a < b, 0 if equal, positive if a > b. Missing parts count as 0. Null if unparsable. */
    fun compare(a: String, b: String): Int? {
        val pa = parse(a) ?: return null
        val pb = parse(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String): Boolean = (compare(candidate, installed) ?: 0) > 0
}
