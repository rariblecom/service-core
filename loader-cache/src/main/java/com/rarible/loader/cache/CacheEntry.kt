package com.rarible.loader.cache

import com.rarible.core.loader.LoadTaskStatus
import java.time.Instant

/**
 * Cache entry payload and associated metadata, obtained from [CacheLoaderService.get].
 */
sealed class CacheEntry<T> {
    /**
     * The entry with [data] was cached at [cachedAt].
     */
    data class Loaded<T>(
        val cachedAt: Instant,
        val data: T
    ) : CacheEntry<T>()

    /**
     * The entry with [data] was cached at [cachedAt]
     * and there is an ongoing update going [updateStatus].
     */
    data class LoadedAndUpdateScheduled<T>(
        val cachedAt: Instant,
        val data: T,
        val updateStatus: LoadTaskStatus.Pending
    ) : CacheEntry<T>()

    /**
     * The entry with [data] was cached at [cachedAt]
     * and the update failed with status [failedUpdateStatus].
     */
    data class LoadedAndUpdateFailed<T>(
        val cachedAt: Instant,
        val data: T,
        val failedUpdateStatus: LoadTaskStatus.Failed
    ) : CacheEntry<T>()

    /**
     * The entry was initially scheduled for loading but has not loaded nor failed yet.
     * Loading status can be seen at [loadStatus].
     */
    data class InitialLoadScheduled<T>(
        val loadStatus: LoadTaskStatus.Pending
    ) : CacheEntry<T>()

    /**
     * The entry was initially scheduled for loading but the loading has failed with status [failedStatus].
     */
    data class InitialFailed<T>(
        val failedStatus: LoadTaskStatus.Failed
    ) : CacheEntry<T>()

    /**
     * The entry is not available yet, consider scheduling an update.
     */
    class NotAvailable<T> : CacheEntry<T>() {
        override fun hashCode(): Int = 0
        override fun equals(other: Any?): Boolean = other is NotAvailable<*>
    }
}
