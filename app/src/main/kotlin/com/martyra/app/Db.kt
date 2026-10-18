package com.martyra.app

import android.content.Context
import android.database.Cursor
import android.os.Build
import net.zetetic.database.sqlcipher.SQLiteDatabase

const val CAPTURED = 0
const val SYNCING = 1
const val UPLOADED = 2
const val ANCHORED = 3
const val CONFIRMED = 4

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

class Db(ctx: Context) : AutoCloseable {
    companion object {
        init {
            System.loadLibrary("sqlcipher")
        }
    }

    private val db: SQLiteDatabase

    init {
        val f = ctx.getDatabasePath("evidence.db").also { it.parentFile?.mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(f, dbPassphrase(ctx), null, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS evidence(" +
                "file TEXT PRIMARY KEY," +
                "session INTEGER NOT NULL," +
                "seq INTEGER NOT NULL," +
                "ts INTEGER NOT NULL," +
                "lat REAL," +
                "lon REAL," +
                "sha256 TEXT NOT NULL," +
                "kind TEXT NOT NULL CHECK(kind IN('video','photo'))," +
                "device TEXT NOT NULL," +
                "status INTEGER NOT NULL DEFAULT 0 CHECK(status BETWEEN 0 AND 4))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status ON evidence(status)")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS status_forward BEFORE UPDATE OF status ON evidence " +
                "WHEN NEW.status < OLD.status BEGIN " +
                "SELECT RAISE(ABORT, 'status moves forward only'); END"
        )
    }

    fun insertOrIgnore(e: Evidence) {
        db.execSQL(
            "INSERT OR IGNORE INTO evidence VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(e.file, e.session, e.seq, e.ts, e.lat, e.lon, e.sha256, e.kind, e.device, e.status)
        )
    }

    fun pending(): List<Evidence> {
        val list = mutableListOf<Evidence>()
        val c: Cursor = db.rawQuery(
            "SELECT file, session, seq, ts, lat, lon, sha256, kind, device, status " +
                "FROM evidence WHERE status < ? " +
                "ORDER BY CASE WHEN kind='photo' THEN 0 ELSE 1 END, session ASC, seq ASC",
            arrayOf("$UPLOADED")
        )
        c.use {
            while (it.moveToNext()) {
                list += Evidence(
                    file = it.getString(0),
                    session = it.getLong(1),
                    seq = it.getInt(2),
                    ts = it.getLong(3),
                    lat = if (it.isNull(4)) null else it.getDouble(4),
                    lon = if (it.isNull(5)) null else it.getDouble(5),
                    sha256 = it.getString(6),
                    kind = it.getString(7),
                    device = it.getString(8),
                    status = it.getInt(9)
                )
            }
        }
        return list
    }

    fun setStatus(file: String, s: Int) {
        db.execSQL("UPDATE evidence SET status=? WHERE file=?", arrayOf(s, file))
    }

    /**
     * Reset a file that got stuck in SYNCING back to CAPTURED so the
     * next worker pass can retry it. The forward-only trigger prevents
     * direct UPDATE, so we delete and re-insert the row.
     */
    fun resetToCapture(file: String) {
        db.beginTransaction()
        try {
            val c = db.rawQuery(
                "SELECT session, seq, ts, lat, lon, sha256, kind, device FROM evidence WHERE file=?",
                arrayOf(file)
            )
            c.use {
                if (it.moveToFirst()) {
                    val s = it.getLong(0); val sq = it.getInt(1); val t = it.getLong(2)
                    val la = if (it.isNull(3)) null else it.getDouble(3)
                    val lo = if (it.isNull(4)) null else it.getDouble(4)
                    val sh = it.getString(5); val k = it.getString(6); val d = it.getString(7)
                    db.execSQL("DELETE FROM evidence WHERE file=?", arrayOf(file))
                    db.execSQL(
                        "INSERT INTO evidence(file,session,seq,ts,lat,lon,sha256,kind,device,status) VALUES(?,?,?,?,?,?,?,?,?,0)",
                        arrayOf(file, s, sq, t, la, lo, sh, k, d)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun ensurePending(file: String) {
        val c = db.rawQuery("SELECT status FROM evidence WHERE file=?", arrayOf(file))
        val st = c.use { if (it.moveToFirst()) it.getInt(0) else null }
        if (st != null && st >= UPLOADED) resetToCapture(file)
    }

    override fun close() {
        runCatching { db.close() }
    }
}

fun parseEvidence(name: String): Evidence? = runCatching {
    val base = name.removeSuffix(".enc")
    val dot = base.lastIndexOf('.')
    val ext = base.substring(dot + 1)
    val p = base.substring(0, dot).split("_")
    val n = p.size
    Evidence(
        file = name,
        session = p[n - 6].toLong(),
        seq = p[n - 5].toInt(),
        ts = p[n - 4].toLong(),
        lat = p[n - 3].toDoubleOrNull(),
        lon = p[n - 2].toDoubleOrNull(),
        sha256 = p[n - 1],
        kind = if (ext == "jpg") "photo" else "video",
        device = Build.MODEL ?: "unknown",
        status = CAPTURED
    )
}.getOrNull()
