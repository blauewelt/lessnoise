package ch.lessnoise.app.upload

import ch.lessnoise.dsp.geo.LocationNoise
import java.security.SecureRandom
import java.util.Base64

/**
 * The phone's location-noise secret (server/DESIGN.md §2 rule 2): read it, or make and store a new
 * one ([LocationNoise.SECRET_BYTES] random bytes) on first use. It never leaves the phone.
 * «Neu starten» and «Vom Server löschen» drop it (Uploader.forget), so the next
 * shared measurement gets a new secret and every place a new offset.
 */
object NoiseSecret {
    fun getOrCreate(store: UploadStore, random: SecureRandom = SecureRandom()): ByteArray {
        val s = store.update { if (it.noiseSecret != null) it else it.copy(noiseSecret = encode(LocationNoise.newSecret(random))) }
        val bytes = decode(s.noiseSecret!!)
        if (bytes != null && bytes.size == LocationNoise.SECRET_BYTES) return bytes
        // Unreadable (should not happen): replace it.
        val fresh = LocationNoise.newSecret(random)
        store.update { it.copy(noiseSecret = encode(fresh)) }
        return fresh
    }

    private fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun decode(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
        null
    }
}
