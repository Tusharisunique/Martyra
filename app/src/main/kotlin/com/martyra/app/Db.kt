package com.martyra.app

import android.content.Context
import android.os.Build
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

private const val DB_NAME = "evidence.db"
private const val DB_VERSION = 1

const val STATUS_CAPTURED = 0
const val STATUS_SYNCING = 1
const val STATUS_UPLOADED = 2

data class Evidence(
    val file: String,
    val session: Long,
    val seq: Int,
    val ts: Long,
    val lat: Double?,
    val lon: Double?,
    val sha256: String,
    val kind: String,
    val device: String,
    val status: Int
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    private val pass: CharArray

    init {
        System.loadLibrary("sqlcipher")
        pass = dbPassphrase(ctx).map { it.toInt().toChar() }.toCharArray()
    }

    private fun rw(): SQLiteDatabase = getWritableDatabase(pass)
    private fun ro(): SQLiteDatabase = getReadableDatabase(pass)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS evidence(
                file TEXT PRIMARY KEY,
                session INTEGER NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL,
                lat REAL, lon REAL,
                sha256 TEXT NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN('video','photo')),
                device TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 0 CHECK(status BETWEEN 0 AND 4))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status ON evidence(status)")
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS status_forward
                BEFORE UPDATE OF status ON evidence
                WHEN NEW.status < OLD.status
                BEGIN SELECT RAISE(ABORT,'status moves forward only'); END"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertOrIgnore(e: Evidence) {
        rw().execSQL(
            "INSERT OR IGNORE INTO evidence VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(e.file, e.session, e.seq, e.ts, e.lat, e.lon, e.sha256, e.kind, e.device, e.status)
        )
    }

    fun pending(): List<Evidence> {
        val list = mutableListOf<Evidence>()
        ro().rawQuery(
            "SELECT * FROM evidence WHERE status < ? ORDER BY ts ASC",
            arrayOf(STATUS_UPLOADED.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list += Evidence(
                    c.getString(0), c.getLong(1), c.getInt(2), c.getLong(3),
                    if (c.isNull(4)) null else c.getDouble(4),
                    if (c.isNull(5)) null else c.getDouble(5),
                    c.getString(6), c.getString(7), c.getString(8), c.getInt(9)
                )
            }
        }
        return list
    }

    fun setStatus(file: String, status: Int) {
        rw().execSQL("UPDATE evidence SET status=? WHERE file=?", arrayOf(status, file))
    }
}

fun parseEvidence(fileName: String): Evidence? = runCatching {
    val base = fileName.removeSuffix(".enc")
    val dotIdx = base.lastIndexOf('.')
    val ext = base.substring(dotIdx + 1)
    val parts = base.substring(0, dotIdx).split("_")
    val n = parts.size
    val sha = parts[n - 1]
    val lon = parts[n - 2].toDoubleOrNull()
    val lat = parts[n - 3].toDoubleOrNull()
    val ts = parts[n - 4].toLong()
    val seq = parts[n - 5].toInt()
    val session = parts[n - 6].toLong()
    val kind = if (ext == "jpg") "photo" else "video"
    Evidence(fileName, session, seq, ts, lat, lon, sha, kind, Build.MODEL, STATUS_CAPTURED)
}.getOrNull()
