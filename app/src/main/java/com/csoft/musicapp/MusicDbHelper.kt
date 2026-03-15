package com.csoft.musicapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MusicDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "musicapp.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_TRACKS = "tracks"
        private const val COL_ID = "id"
        private const val COL_NAME = "name"
        private const val COL_URI = "uri"
        private const val COL_DIR_URI = "dir_uri"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val sql = """
            CREATE TABLE $TABLE_TRACKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT,
                $COL_URI TEXT UNIQUE,
                $COL_DIR_URI TEXT
            );
        """.trimIndent()
        db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
        onCreate(db)
    }

    fun insertTracks(dirUri: String, tracks: List<MusicFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR IGNORE INTO $TABLE_TRACKS ($COL_NAME,$COL_URI,$COL_DIR_URI) VALUES (?,?,?)")
            for (t in tracks) {
                stmt.bindString(1, t.name)
                stmt.bindString(2, t.uri.toString())
                stmt.bindString(3, dirUri)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getTracksForDir(dirUri: String): List<MusicFile> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRACKS,
            arrayOf(COL_NAME, COL_URI),
            "$COL_DIR_URI = ?",
            arrayOf(dirUri),
            null,
            null,
            COL_NAME + " ASC"
        )
        val results = mutableListOf<MusicFile>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: "Unknown"
                val uriStr = it.getString(1) ?: continue
                results.add(MusicFile(name, android.net.Uri.parse(uriStr)))
            }
        }
        return results
    }
}
