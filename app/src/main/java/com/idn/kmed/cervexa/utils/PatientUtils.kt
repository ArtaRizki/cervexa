package com.idn.kmed.cervexa.utils

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object PatientUtils {

    /** Hitung usia (tahun) dari DOB UTC millis. */
    fun calculateAge(dobUtcMs: Long): Int {
        if (dobUtcMs <= 0) return 0
        val tz = TimeZone.getTimeZone("UTC")
        val dob = Calendar.getInstance(tz).apply { timeInMillis = dobUtcMs }
        val now = Calendar.getInstance(tz)

        var age = now.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
        if (now.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) age--
        return age.coerceIn(0, 130)
    }

    /** Buat nama folder: NIK_NAMA_USIA (spasi jadi underscore, trim). */
    fun buildFolderName(nik: String, nama: String, dobUtcMs: Long): String {
        val age = calculateAge(dobUtcMs)
        val safeNik = nik.trim()
        val safeNama = nama.trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^A-Za-z0-9_\\-]".toRegex(), "") // buang char aneh
        return "${safeNik}_${safeNama}_${age}"
    }
}