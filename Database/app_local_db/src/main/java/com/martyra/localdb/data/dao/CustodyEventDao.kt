package com.martyra.localdb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.martyra.localdb.data.entity.CustodyEventEntity

/**
 * Data Access Object for the [custody_events] table.
 *
 * This DAO exposes INSERT and SELECT operations only.
 * UPDATE and DELETE are permanently blocked at the database trigger level;
 * calling any Room @Update or @Delete on this table would throw a SQLiteException
 * at runtime (ABORT 'custody_events is append-only').
 */
@Dao
interface CustodyEventDao {

    /**
     * Append a new custody event.
     * The event_hash and prev_event_hash must be computed by the caller via
     * [CustodyChain.computeEventHash] before calling this method.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: CustodyEventEntity): Long

    /** Full chain in ascending chronological order (ascending event_id). */
    @Query("SELECT * FROM custody_events WHERE evidence_id = :evidenceId ORDER BY event_id ASC")
    suspend fun getChain(evidenceId: String): List<CustodyEventEntity>

    /** Latest event for a given evidence item (used for chain-linking new events). */
    @Query("""
        SELECT * FROM custody_events
        WHERE evidence_id = :evidenceId
        ORDER BY event_id DESC
        LIMIT 1
    """)
    suspend fun getLatestEvent(evidenceId: String): CustodyEventEntity?

    /**
     * Latest event_hash for chain-linking.
     * Returns NULL if there are no events yet for this evidence (meaning the next event
     * must use the all-zero predecessor hash).
     */
    @Query("""
        SELECT event_hash FROM custody_events
        WHERE evidence_id = :evidenceId
        ORDER BY event_id DESC
        LIMIT 1
    """)
    suspend fun getLatestEventHash(evidenceId: String): String?

    @Query("SELECT COUNT(*) FROM custody_events WHERE evidence_id = :evidenceId")
    suspend fun countEvents(evidenceId: String): Int
}
