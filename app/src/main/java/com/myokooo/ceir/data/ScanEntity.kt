package com.myokooo.ceir.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val imei1: String,
    val imei2: String? = null,

    // IMEI 1 Status
    val imei1Status: String? = null,
    val imei1TaxPayment: String? = null,
    val imei1BlockingStatus: String? = null,

    // IMEI 2 Status
    val imei2Status: String? = null,
    val imei2TaxPayment: String? = null,
    val imei2BlockingStatus: String? = null,

    // Device Info
    val brand: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val operatingSystem: String? = null,
    val deviceType: String? = null,
    val allocationDate: String? = null,

    // Metadata
    val scannedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val syncStatus: Int = 0
)
