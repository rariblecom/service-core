package com.rarible.core.reduce.service

import com.rarible.core.reduce.model.DataKey
import com.rarible.core.reduce.model.ReduceEvent
import com.rarible.core.reduce.model.ReduceSnapshot
import kotlinx.coroutines.flow.Flow

interface Reducer<Event : ReduceEvent<Mark>, Snapshot : ReduceSnapshot<Data, Mark, Key>, Mark : Comparable<Mark>, Data, Key : DataKey> {
    fun getDataKeyFromEvent(event: Event): Key

    fun getInitialData(key: Key): Snapshot

    suspend fun reduce(initial: Snapshot, events: Flow<Event>): Snapshot
}