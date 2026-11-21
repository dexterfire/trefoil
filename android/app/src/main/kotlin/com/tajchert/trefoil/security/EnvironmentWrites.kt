package com.tajchert.trefoil.security

import com.tajchert.trefoil.core.model.SavedEnvironment

/**
 * The view model's reads and writes of the saved machines. Every write is a read-modify-write of what the vault holds
 * now, under the process-wide lock the usage widget's renewals also take, so a write never brings back an older copy.
 *
 * A failed read is retried once. If it still fails and the vault has a list known in this process ([EnvironmentStore.lastKnown]:
 * the last list any caller read or wrote), the change applies to that list instead, as the app did before writes re-read
 * the vault; only then can a token the widget renewed meanwhile be lost. A change never applies to an empty list because
 * a read failed, except that a new pairing may replace a vault never readable in this process (the user was asked to
 * pair again); any other write then fails.
 */
internal class EnvironmentWrites(private val store: EnvironmentStore) {
    fun load(): List<SavedEnvironment> = store.read()

    /** Applies [change] and returns what is stored afterwards. Throws when nothing could be written. */
    fun write(newPairing: Boolean = false, change: (List<SavedEnvironment>) -> List<SavedEnvironment>): List<SavedEnvironment> =
        store.locked {
            val current = try { store.read() } catch (_: Exception) {
                try { store.read() } catch (error: Exception) { store.lastKnown ?: if (newPairing) emptyList() else throw error }
            }
            val next = change(current)
            if (next != current) store.write(next)
            next
        }
}
