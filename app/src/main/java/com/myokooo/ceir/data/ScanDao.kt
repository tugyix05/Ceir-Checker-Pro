package com.myokooo.ceir.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scan: ScanEntity)

    @Delete
    suspend fun delete(scan: ScanEntity)

    @Query("SELECT * FROM scans ORDER BY scannedAt DESC")
    fun getAll(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE syncStatus = 0")
    suspend fun getPending(): List<ScanEntity>

    @Query("SELECT COUNT(*) FROM scans")
    suspend fun count(): Int

    @Query("UPDATE scans SET syncStatus = 1, syncedAt = :ts WHERE id = :id")
    suspend fun markSynced(id: String, ts: Long = System.currentTimeMillis())

    /**
     * Item 12 — same-device dedup: order-insensitive IMEI pair match.
     * `IS` is null-safe so single-IMEI rows (imei2 NULL) match correctly.
     */
    @Query("""SELECT * FROM scans WHERE
        (imei1 = :a AND imei2 IS :b) OR
        (imei1 = :b AND imei2 IS :a)
        LIMIT 1""")
    suspend fun findByImeiPair(a: String, b: String?): ScanEntity?

    /** Item 13 — Clear button: local history only (cloud untouched). */
    @Query("DELETE FROM scans")
    suspend fun deleteAll()
}
