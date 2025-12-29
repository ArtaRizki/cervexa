package com.idn.kmed.cervexa.model

import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId


data class PatientItem(
    val nama: String,
    val nik: String,
    val nrm: String?,
    val rs: String?,
    val dobUtcMs: Long
) {
    val ageYears: Int
        get() {
            if (dobUtcMs <= 0L) return 0
            return try {
                val zone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.time.ZoneId.of("Asia/Jakarta")
                } else {
                    TODO("VERSION.SDK_INT < O")
                }
                val dob = java.time.Instant.ofEpochMilli(dobUtcMs)
                    .atZone(zone)
                    .toLocalDate()
                val today = java.time.LocalDate.now(zone)
                java.time.Period.between(dob, today).years
            } catch (e: Exception) {
                0
            }
        }
}
