package com.martyra.localdb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.martyra.localdb.data.entity.EvidenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the [evidence] table.
 *
 * Important: All UPDATE queries touch ONLY the mutable columns (status, server_tx_id,
 * merkle_proof). Attempting to change an immutable column will abort at the SQLite trigger level.
 * Never add an @Update(entity = EvidenceEntity) here — it would try to write all fields.
 */
@Dao
interface EvidenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(evidence: EvidenceEntity): Long

    @Query("SELECT * FROM evidence WHERE evidence_id = :evidenceId")
    suspend fun getById(evidenceId: String): EvidenceEntity?

    @Query("SELECT * FROM evidence WHERE status = :status ORDER BY captured_at DESC")
    suspend fun getByStatus(status: String): List<EvidenceEntity>

    /** Reactive stream — emits a new list whenever the status-filtered rows change. */
    @Query("SELECT * FROM evidence WHERE status = :status ORDER BY captured_at DESC")
    fun observeByStatus(status: String): Flow<List<EvidenceEntity>>

    @Query("SELECT * FROM evidence WHERE session_id = :sessionId ORDER BY captured_at DESC")
    suspend fun getBySession(sessionId: String): List<EvidenceEntity>

    @Query("""
        SELECT * FROM evidence
        WHERE captured_at BETWEEN :startMs AND :endMs
        ORDER BY captured_at DESC
    """)
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<EvidenceEntity>

    /**
     * Update only the mutable [status] column.
     * The enforce_evidence_immutable trigger will ABORT if any immutable field is touched.
     */
    @Query("UPDATE evidence SET status = :newStatus WHERE evidence_id = :evidenceId")
    suspend fun updateStatus(evidenceId: String, newStatus: String): Int

    /** Update status + anchoring fields atomically. Used by markAnchored(). */
    @Query("""
        UPDATE evidence
        SET status = :newStatus, server_tx_id = :txId, merkle_proof = :merkleProof
        WHERE evidence_id = :evidenceId
    """)
    suspend fun updateAnchored(
        evidenceId: String,
        newStatus: String,
        txId: String,
        merkleProof: String?,
    ): Int

    /**
     * Returns evidence items whose file still needs to be present locally.
     * Excludes FILE_SYNCED, ANCHORED, CONFIRMED (file may have been offloaded),
     * and FILE_MISSING (already detected and recorded).
     */
    @Query("""
        SELECT * FROM evidence
        WHERE status NOT IN ('FILE_SYNCED', 'ANCHORED', 'CONFIRMED', 'FILE_MISSING', 'SYNC_FAILED')
        ORDER BY captured_at ASC
    """)
    suspend fun getEvidenceRequiringLocalFile(): List<EvidenceEntity>
}
