package com.martyra.localdb.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a recording session — a bounded time window during which evidence was captured.
 *
 * A session groups related evidence items (e.g., everything captured during a protest).
 * session_id is a client-generated UUID so it can be referenced before the row is committed.
 *
 * OS concept: sandbox_pid optionally records the OS process ID of the capture module at the time
 * it initiated the session. This supports a future IPC audit trail showing which PID wrote the data.
 */
@Entity(tableName = "capture_sessions")
data class CaptureSessionEntity(

    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** UTC epoch milliseconds when the session was started. */
    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /** UTC epoch milliseconds when the session was closed. NULL if still open. */
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

    /** Stable device identifier (e.g. hardware-attested ID). */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** Application version string at the time the session was created. */
    @ColumnInfo(name = "app_version")
    val appVersion: String,

    /**
     * Process ID of the capture module that opened this session.
     * NULL if capture runs in-process or the PID is not recorded.
     * Future hardening: verify this against a signed IPC token from the capture service.
     */
    @ColumnInfo(name = "sandbox_pid")
    val sandboxPid: Int? = null,
)
