package com.kiyoscripts.nexusclone.core.instance

/**
 * One clone of one package.
 *
 * The identity of an instance is the pair (userId, packageName). BlackBox allocates a
 * virtual user id per clone, so two clones of the same package differ only by userId.
 */
data class CloneInstance(
    /** Stable row id, monotonic. */
    val id: Long = 0L,
    /** BlackBox virtual user id. Unique per clone. */
    val userId: Int,
    val packageName: String,
    /** User-visible label, defaults to the app label at clone time. */
    val label: String,
    val state: InstanceState = InstanceState.CREATED,
    /** Epoch millis. */
    val createdAt: Long = System.currentTimeMillis(),
    val lastLaunchAt: Long = 0L,
    /** Bytes, refreshed by [InstanceManager.refreshSizes]. */
    val dataSizeBytes: Long = 0L,
    /** Soft quota in bytes, 0 means unlimited. */
    val quotaBytes: Long = 0L,
    /** Relative path under the preview cache dir, null when no thumbnail exists. */
    val previewPath: String? = null,
    val previewUpdatedAt: Long = 0L,
    /** Keep-alive strength 0..2, see KeepAliveController. */
    val keepAliveLevel: Int = 0,
    /** Proxy profile id, null when direct. */
    val proxyId: Long? = null,
    /** Identity profile id, null when the container default is used. */
    val identityId: Long? = null,
    val frozen: Boolean = false
) {
    val key: String get() = "$userId:$packageName"

    val isOverQuota: Boolean
        get() = quotaBytes > 0L && dataSizeBytes > quotaBytes
}
