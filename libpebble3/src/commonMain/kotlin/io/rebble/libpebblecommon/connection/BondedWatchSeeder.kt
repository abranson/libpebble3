package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.database.entity.TransportType

/** One logical bonded watch, after platform aliases for its transports have been merged. */
internal data class BondedWatchCandidate(
    val identity: String,
    val name: String,
    val addresses: Map<TransportType, String>,
    val aliases: Int = 0,
)

/**
 * Enumerate identifiable bonded watches. `null` means that the platform bond source is
 * temporarily unavailable; an empty list means that enumeration succeeded and found none.
 */
internal expect suspend fun bondedWatchCandidates(
    appContext: AppContext,
): List<BondedWatchCandidate>?

/**
 * The previous Linux companion used the Bluetooth name to join BlueZ's LE and BR/EDR objects.
 * Keep the same narrow normalization: `Pebble Time Le ABCD` and `Pebble Time ABCD` are one watch.
 */
internal fun bondedWatchIdentity(name: String, fallbackAddress: String): String {
    val normalized = name.trim()
        .replace(Regex("-le", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+le\\s+", RegexOption.IGNORE_CASE), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()
    return if (normalized.isEmpty()) {
        "address:${fallbackAddress.uppercase()}"
    } else {
        "name:$normalized"
    }
}

internal fun mergeBondedWatchCandidates(
    candidates: List<BondedWatchCandidate>,
): List<BondedWatchCandidate> = candidates
    .groupBy(BondedWatchCandidate::identity)
    .map { (identity, aliases) ->
        val addresses = TransportType.entries.mapNotNull { transport ->
            aliases.asSequence()
                .mapNotNull { it.addresses[transport] }
                .map { it.uppercase() }
                .minOrNull()
                ?.let { transport to it }
        }.toMap()
        val selectedName = aliases
            .sortedWith(
                compareBy<BondedWatchCandidate> {
                    it.name.contains(" Le ", ignoreCase = true) ||
                        it.name.contains("-Le", ignoreCase = true)
                }.thenBy { it.name.lowercase() },
            )
            .first()
            .name
        BondedWatchCandidate(
            identity = identity,
            name = selectedName,
            addresses = addresses,
            aliases = aliases
                .fold(0L) { count, candidate ->
                    count + candidate.aliases.coerceAtLeast(0).toLong() + 1L
                }
                .minus(1L)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt(),
        )
    }
    .sortedBy(BondedWatchCandidate::identity)

sealed class BondedWatchImportOutcome {
    data class Completed(
        val imported: Int,
        val existing: Int,
        val aliases: Int,
        val unsupported: Int,
    ) : BondedWatchImportOutcome() {
        init {
            require(imported >= 0 && existing >= 0 && aliases >= 0 && unsupported >= 0)
        }
    }

    data object Unavailable : BondedWatchImportOutcome()
    data object Cancelled : BondedWatchImportOutcome()
    data object PersistenceFailed : BondedWatchImportOutcome()
}

const val UNKNOWN_WATCH_SERIAL_OR_VERSION = "unknown"
