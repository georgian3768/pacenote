package com.pocketmechanic.spike

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pocketmechanic.shift.TripSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recorded trip. Aggregates only: storing every frame would be ~215k rows per
 * drive and nothing in the app needs them (the CSV of a raw capture already
 * exists on the ESP32 side).
 */
@Entity(tableName = "trips")
data class TripRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val distanceKm: Double,
    val maxSpeedKph: Double,
    val maxRpm: Int,
    val avgRpm: Int,
    val movingMs: Long,
    val idleMs: Long,
    val overRevMs: Long,
    val hardAccelCount: Int,
    val ecoScore: Int,
    val distRawDelta: Long?,
    val distRawRatio: Double?,
    val parkedLat: Double? = null,
    val parkedLon: Double? = null,
    /**
     * True for the bench demo. The demo is app-side data, not the car's, so a
     * trip started by it must never survive as a real drive - not even when the
     * process dies mid-demo and startup salvage would otherwise close it.
     */
    val synthetic: Boolean = false,
    /**
     * True when the redline the trip was scored against had been confirmed by the
     * driver. An unconfirmed redline is a placeholder, so the over-rev time (and
     * with it the eco score) of such a trip is provisional.
     */
    val redlineConfirmed: Boolean = false,
    /**
     * GPS scale the trip's speeds were recorded under. The ladder and the shift
     * points are only comparable across trips when this matches, so a trip must
     * carry the scale it was actually driven with (trip #19 was captured before
     * the 0.893 calibration, and its ratios are trip #20's divided by it).
     */
    val speedScale: Double = 1.0,
)

/**
 * Blocking DAO on purpose: Room's suspend support did not survive kapt here
 * ("Not sure how to handle insert method's return type"), and every caller is
 * already on a background dispatcher, so the coroutine variant bought nothing.
 */
/**
 * One telemetry frame of a trip, at the stream's own rate (~10 Hz).
 *
 * Storing frames is what makes the laptop unnecessary: the shift thresholds and
 * the throttle scaling can be re-fitted from this CSV alone. A 30-minute drive is
 * ~18k rows, so this stays small; raw CAN frames are deliberately not stored.
 */
@Entity(tableName = "trip_frames", primaryKeys = ["tripId", "tMs"])
data class TripFrameRow(
    val tripId: Long,
    val tMs: Long,
    val rpm: Float?,
    val speedKph: Float?,
    val thr1: Int?,
    val thr2: Int?,
    val brake: Boolean?,
    val engineRunning: Boolean?,
    val canActive: Boolean,
    val tempRaw: Int?,
    val distRaw: Int?,
    val volt: Float?,
)

@Dao
interface TripDao {
    @Insert
    fun insert(trip: TripRow): Long

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun all(): List<TripRow>

    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT 1")
    fun latest(): TripRow?

    @Query("SELECT COALESCE(SUM(distanceKm), 0.0) FROM trips")
    fun totalKm(): Double

    /**
     * IGNORE, not ABORT: a batch must not be lost because one row collides with
     * a frame already written (the stream can emit two frames in the same
     * millisecond). Before this, every collision killed the whole batch and the
     * log filled with "frame flush failed: SQLiteConstraintException".
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFrames(frames: List<TripFrameRow>): List<Long>

    @Query("SELECT * FROM trip_frames WHERE tripId = :id ORDER BY tMs")
    fun frames(id: Long): List<TripFrameRow>

    @Query("SELECT COUNT(*) FROM trip_frames WHERE tripId = :id")
    fun frameCount(id: Long): Int

    @Update
    fun update(trip: TripRow)

    @Query("UPDATE trips SET parkedLat = :lat, parkedLon = :lon WHERE id = :id")
    fun setParked(id: Long, lat: Double?, lon: Double?)

    @Query("DELETE FROM trips WHERE id = :id")
    fun delete(id: Long)

    /** Frames have no foreign key, so a dropped trip must delete them itself. */
    @Query("DELETE FROM trip_frames WHERE tripId = :id")
    fun deleteFrames(id: Long)

    @Query("SELECT * FROM trips WHERE endedAt = 0 ORDER BY startedAt")
    fun unfinished(): List<TripRow>
}

@Database(entities = [TripRow::class, TripFrameRow::class], version = 5, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {
    abstract fun trips(): TripDao

    companion object {
        @Volatile
        private var instance: TripDatabase? = null

        /**
         * Version 3 marks demo trips so start-up salvage can drop them instead
         * of saving bench data as a real drive. A real ALTER keeps the drives
         * already on the phone - the version 2 fallback wiped everything, which
         * is exactly what salvage now tries to avoid.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN synthetic INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Version 4 records whether the trip was scored against a confirmed
         * redline. Existing rows default to 0 (unconfirmed): every drive before
         * this ran against the 6100 rpm placeholder, so calling them confirmed
         * would be a lie.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    // Room maps the field name as-is, so the column is camelCase
                    // like the rest of this table (a snake_case column makes the
                    // schema check fail after the ALTER).
                    "ALTER TABLE trips ADD COLUMN redlineConfirmed INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Version 5 stores the GPS scale the trip's speeds were recorded under.
         * The shift ladder is only comparable when the scale matches (trip #19
         * predates the 0.893 calibration), so each row must remember its own.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE trips ADD COLUMN speedScale REAL NOT NULL DEFAULT 1.0"
                )
            }
        }

        fun get(context: Context): TripDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TripDatabase::class.java,
                "trips.db"
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

/** Row builders and the CSV export. Pure formatting, no I/O. */
object TripFormat {

    fun row(
        summary: TripSummary,
        startedAt: Long,
        endedAt: Long,
        redlineConfirmed: Boolean,
        speedScale: Double = 1.0,
    ): TripRow = TripRow(
        startedAt = startedAt,
        endedAt = endedAt,
        distanceKm = summary.distanceKm,
        maxSpeedKph = summary.maxSpeedKph,
        maxRpm = summary.maxRpm,
        avgRpm = summary.avgRpm,
        movingMs = summary.movingMs,
        idleMs = summary.idleMs,
        overRevMs = summary.overRevMs,
        hardAccelCount = summary.hardAccelCount,
        ecoScore = summary.ecoScore,
        distRawDelta = summary.distRawDelta,
        distRawRatio = summary.distRawRatio,
        redlineConfirmed = redlineConfirmed,
        speedScale = speedScale,
    )

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun stamp(ms: Long): String = iso.format(Date(ms))

    /** One row per frame of one trip. */
    fun framesCsv(trip: TripRow, frames: List<TripFrameRow>): String = buildString {
        appendLine(
            "t_ms,rpm,speed_kph,thr1,thr2,brake,engine_running,can_active,temp_raw,dist_raw,volt"
        )
        val base = frames.firstOrNull()?.tMs ?: trip.startedAt
        frames.forEach { f ->
            appendLine(
                listOf(
                    (f.tMs - base).toString(),
                    f.rpm?.let { "%.2f".format(Locale.US, it) } ?: "",
                    f.speedKph?.let { "%.2f".format(Locale.US, it) } ?: "",
                    f.thr1 ?: "",
                    f.thr2 ?: "",
                    f.brake?.toString() ?: "",
                    f.engineRunning?.toString() ?: "",
                    f.canActive,
                    f.tempRaw ?: "",
                    f.distRaw ?: "",
                    f.volt?.let { "%.2f".format(Locale.US, it) } ?: "",
                ).joinToString(",")
            )
        }
    }

    /**
     * Header matches the column order of the table, one row per trip. Values are
     * deliberately raw (metres/ms kept as km/ms) so the CSV can be checked by
     * hand against the dashboard.
     */
    fun csv(trips: List<TripRow>): String = buildString {
        appendLine(
            "started,ended,distance_km,max_speed_kph,max_rpm,avg_rpm,moving_s,idle_s," +
                "over_rev_s,hard_accels,eco_score,dist_raw_delta,dist_raw_per_km,lat,lon," +
                "redline_confirmed,speed_scale"
        )
        trips.forEach { t ->
            appendLine(
                listOf(
                    stamp(t.startedAt),
                    stamp(t.endedAt),
                    "%.3f".format(Locale.US, t.distanceKm),
                    "%.1f".format(Locale.US, t.maxSpeedKph),
                    t.maxRpm,
                    t.avgRpm,
                    "%.1f".format(Locale.US, t.movingMs / 1000.0),
                    "%.1f".format(Locale.US, t.idleMs / 1000.0),
                    "%.1f".format(Locale.US, t.overRevMs / 1000.0),
                    t.hardAccelCount,
                    t.ecoScore,
                    t.distRawDelta ?: "",
                    t.distRawRatio?.let { "%.1f".format(Locale.US, it) } ?: "",
                    t.parkedLat?.let { "%.6f".format(Locale.US, it) } ?: "",
                    t.parkedLon?.let { "%.6f".format(Locale.US, it) } ?: "",
                    t.redlineConfirmed,
                    "%.4f".format(Locale.US, t.speedScale),
                ).joinToString(",")
            )
        }
    }
}
