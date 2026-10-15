package com.martyra.localdb.data.db

import androidx.room.TypeConverter

/**
 * Room type converters.
 *
 * All entity fields currently use types Room handles natively (String, Long, Int, Double, nullable
 * variants of each). This class is included for future extensibility — for example, if we later
 * store a Kotlin enum directly rather than its String name, or an Instant instead of a Long.
 *
 * Status and event-type values are stored as String (the .name of the enum) rather than ordinal,
 * so schema data is human-readable and resilient to enum reordering.
 */
class DatabaseConverters {
    // No converters needed for the current schema.
    // Add @TypeConverter methods here when new types are introduced.

    companion object {
        /**
         * Example pattern for future enum converters:
         *
         *   @TypeConverter
         *   fun fromEvidenceStatus(status: EvidenceStatus): String = status.name
         *
         *   @TypeConverter
         *   fun toEvidenceStatus(name: String): EvidenceStatus = EvidenceStatus.valueOf(name)
         *
         * Storing enum.name rather than enum.ordinal means the schema is stable across refactors
         * that reorder the enum constants.
         */
    }
}
