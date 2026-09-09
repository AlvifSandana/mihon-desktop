package mihon.desktop.loader.catalog

/**
 * Version comparison for extension updates.
 *
 * Primary path (used whenever both sides have one): compare `versionCode` as a plain
 * Long -- it's the monotonically increasing build number keiyoushi stamps into every
 * release, so it needs no parsing.
 *
 * Fallback path (either side lacks a versionCode): compare the version *name*
 * semver-ish. Each dot-separated segment is split into a leading numeric part and a
 * non-numeric suffix:
 * - numeric parts compare numerically (`1.2 < 1.10`, `1.2.3 < 1.2.4`);
 * - a missing segment counts as `0` (`1.2 == 1.2.0`);
 * - when the numeric parts tie, the suffixes compare as plain strings, with the empty
 *   suffix sorting lowest (`1.2 < 1.2-beta`). This is deliberately simpler than
 *   Mihon's pre-release convention -- for update detection the versionCode path does
 *   the real work; the name path only needs to be deterministic.
 */
object VersionComparator {

    /** @return negative if [a] is older than [b], 0 if equal, positive if newer. */
    fun compareVersionNames(a: String?, b: String?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1

        val segmentsA = a.split('.')
        val segmentsB = b.split('.')
        val count = maxOf(segmentsA.size, segmentsB.size)
        for (i in 0 until count) {
            val segA = parseSegment(segmentsA.getOrNull(i))
            val segB = parseSegment(segmentsB.getOrNull(i))
            if (segA.first != segB.first) return segA.first.compareTo(segB.first)
            val suffixCompare = segA.second.compareTo(segB.second)
            if (suffixCompare != 0) return suffixCompare
        }
        return 0
    }

    /**
     * Whether the catalog version (`new*`) is strictly newer than the installed one
     * (`installed*`). versionCode wins when the installed jar has one; otherwise the
     * version names are compared.
     */
    fun isNewerVersion(
        installedVersionCode: Long?,
        installedVersionName: String?,
        newVersionCode: Long,
        newVersionName: String,
    ): Boolean {
        if (installedVersionCode != null) return newVersionCode > installedVersionCode
        return compareVersionNames(installedVersionName, newVersionName) < 0
    }

    /** `"12-beta"` -> `(12, "-beta")`; `null`/blank -> `(0, "")`. */
    private fun parseSegment(segment: String?): Pair<Long, String> {
        if (segment.isNullOrBlank()) return 0L to ""
        var end = 0
        while (end < segment.length && segment[end].isDigit()) end++
        val number = segment.substring(0, end).toLongOrNull() ?: 0L
        return number to segment.substring(end)
    }
}
