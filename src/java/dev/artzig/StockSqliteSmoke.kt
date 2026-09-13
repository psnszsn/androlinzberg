package dev.artzig

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/** Public SQLite contracts supplied by pinned external/sqlite/android. */
internal object StockSqliteSmoke {
    fun run() {
        SQLiteDatabase.create(null).use { db ->
            fun scalar(sql: String, vararg args: String): String? =
                db.rawQuery(sql, args).use { cursor ->
                    check(cursor.moveToFirst())
                    if (cursor.isNull(0)) null else cursor.getString(0)
                }
            db.execSQL("CREATE TABLE names (name TEXT COLLATE LOCALIZED)")
            db.execSQL("CREATE INDEX names_order ON names(name)")
            for (name in listOf("z", "ä", "b"))
                db.execSQL("INSERT INTO names VALUES (?)", arrayOf(name))
            fun order() = db.rawQuery("SELECT name FROM names ORDER BY name", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            db.setLocale(Locale.GERMAN)
            check(order() == listOf("ä", "b", "z")) { "German ICU collation: ${order()}" }
            check(scalar("SELECT 'e' = 'é' COLLATE LOCALIZED") == "1")
            db.setLocale(Locale.forLanguageTag("sv-SE"))
            check(order() == listOf("b", "z", "ä")) { "Locale change must reindex: ${order()}" }
            check(scalar("SELECT 'ä' < 'z' COLLATE UNICODE") == "1")
            check(scalar("SELECT PHONE_NUMBERS_EQUAL(?, ?)", "+1 (650) 555-1234", "6505551234") == "1")
            check(scalar("SELECT PHONE_NUMBERS_EQUAL(?, ?, 1)", "+1 6505551234", "+44 6505551234") == "0")
            check(scalar("SELECT PHONE_NUMBERS_EQUAL(?, ?, 0, 4)", "5551234", "1234") == "1")
            check(scalar("SELECT PHONE_NUMBERS_EQUAL(NULL, '1234')") == null)
            check(scalar("SELECT _PHONE_NUMBER_STRIPPED_REVERSED('+1 (650) 555-1234')") == "43215550561+")
            check(scalar("SELECT _PHONE_NUMBER_STRIPPED_REVERSED(NULL)") == null)
            check(scalar("SELECT _DELETE_FILE(NULL)") == null)
            val root = File(checkNotNull(System.getenv("EXTERNAL_STORAGE")))
            check(root.isDirectory || root.mkdirs())
            val file = File.createTempFile("sqlite-extension-", ".tmp", root)
            try {
                check(scalar("SELECT _DELETE_FILE(?)", "${root.path}/../${file.name}") == null)
                check(file.exists())
                check(scalar("SELECT _DELETE_FILE(?)", file.absolutePath) == "1")
                check(!file.exists())
                check(scalar("SELECT _DELETE_FILE(?)", file.absolutePath) == "0")
            } finally { file.delete() }
        }
        println("StockSqliteSmoke: PASS ICU sorting/reindex, phone functions and file deletion contracts")
    }
}
