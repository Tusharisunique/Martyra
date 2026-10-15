package com.martyra.localdb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.martyra.localdb.data.entity.CaptureSessionEntity

@Dao
interface CaptureSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: CaptureSessionEntity): Long

    @Query("SELECT * FROM capture_sessions WHERE session_id = :sessionId")
    suspend fun getById(sessionId: String): CaptureSessionEntity?

    @Query("SELECT * FROM capture_sessions ORDER BY started_at DESC")
    suspend fun getAll(): List<CaptureSessionEntity>

    @Query("UPDATE capture_sessions SET ended_at = :endedAt WHERE session_id = :sessionId")
    suspend fun updateEndedAt(sessionId: String, endedAt: Long): Int
}
