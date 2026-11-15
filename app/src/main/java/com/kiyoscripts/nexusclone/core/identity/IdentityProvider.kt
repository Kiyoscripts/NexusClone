package com.kiyoscripts.nexusclone.core.identity

import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.core.system.ProcessRecord

/**
 * Bridges the app's identity database into the container engine.
 *
 * The engine builds an [top.niunaijun.blackbox.entity.AppConfig] for every guest process it
 * starts, but it cannot read `nexus_identity.db` — that database belongs to the host app.
 * This provider is registered once at startup so the engine can ask for a clone's identity
 * at bind time and ship it across the process boundary.
 *
 * Without this, `VirtualSpoof.cpp` answers every property read from its compile-time table
 * and every clone reports the same fingerprint and serial, which is exactly the linkability
 * the identity subsystem exists to prevent.
 */
object IdentityProvider : ProcessRecord.SpoofedPropsProvider {

    private const val TAG = "IdentityProvider"

    @Volatile
    private var identityStore: IdentityStore? = null

    @Volatile
    private var instanceStore: InstanceStore? = null

    /**
     * Register the provider with the engine.
     *
     * Safe to call more than once; the engine keeps a single reference.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        identityStore = IdentityStore(app)
        instanceStore = InstanceStore(app)
        ProcessRecord.setSpoofedPropsProvider(this)
        Log.i(TAG, "identity provider installed")
    }

    /**
     * Resolve the alternating name/value identity array for one clone.
     *
     * Returns null when the clone has no identity bound, which leaves the engine's fallback
     * identity in place rather than reporting the real device.
     */
    override fun spoofedPropsFor(packageName: String?, userId: Int): Array<String>? {
        if (packageName == null) return null
        val instances = instanceStore ?: return null
        val identities = identityStore ?: return null
        return try {
            val instance = instances.all().firstOrNull {
                it.userId == userId && it.packageName == packageName
            } ?: return null
            val identityId = instance.identityId ?: return null
            val identity = identities.byId(identityId) ?: return null
            identity.toSpoofPairs()
        } catch (e: Throwable) {
            // A failure here must never break the guest bind: the engine falls back to the
            // shared identity, which is what the app had before this bridge existed.
            Log.w(TAG, "could not resolve identity for $packageName/$userId: ${e.message}")
            null
        }
    }
}
