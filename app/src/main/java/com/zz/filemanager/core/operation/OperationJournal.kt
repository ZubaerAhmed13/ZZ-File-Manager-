package com.zz.filemanager.core.operation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable Step 2 operation journal. Schema version 1 is intentionally additive and never uses
 * destructive migration. The full typed snapshot is persisted atomically per operation while
 * indexed columns keep queue/recovery queries cheap.
 */
class OperationJournal(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE operations (
                id TEXT PRIMARY KEY NOT NULL,
                type TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                snapshot_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_operations_state_created ON operations(state, created_at)")
        db.execSQL("CREATE INDEX idx_operations_updated ON operations(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions must add explicit, non-destructive migrations here.
        if (oldVersion != newVersion) {
            throw IllegalStateException("No operation journal migration from $oldVersion to $newVersion")
        }
    }

    fun upsert(operation: FileOperation) {
        val values = ContentValues().apply {
            put("id", operation.id)
            put("type", operation.type.name)
            put("state", operation.state.name)
            put("created_at", operation.createdAtMillis)
            put("updated_at", operation.updatedAtMillis)
            put("snapshot_json", OperationJsonCodec.encode(operation))
        }
        writableDatabase.insertWithOnConflict("operations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(id: String): FileOperation? = readableDatabase.query(
        "operations",
        arrayOf("snapshot_json"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else decodeSafely(cursor.getString(0))
    }

    fun readAll(): List<FileOperation> = readableDatabase.query(
        "operations",
        arrayOf("snapshot_json"),
        null,
        null,
        null,
        null,
        "created_at ASC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) decodeSafely(cursor.getString(0))?.let(::add)
        }
    }

    fun delete(id: String) {
        writableDatabase.delete("operations", "id = ?", arrayOf(id))
    }

    fun prune(nowMillis: Long, maxCompleted: Int = 100, terminalMaxAgeMillis: Long = 14L * 24L * 60L * 60L * 1000L) {
        val db = writableDatabase
        val threshold = nowMillis - terminalMaxAgeMillis
        val terminal = FileOperationState.entries.filter { it.isTerminal }.joinToString(",") { "'${it.name}'" }
        db.delete("operations", "state IN ($terminal) AND updated_at < ?", arrayOf(threshold.toString()))

        val keepIds = db.rawQuery(
            "SELECT id FROM operations WHERE state IN ($terminal) ORDER BY updated_at DESC LIMIT ?",
            arrayOf(maxCompleted.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (keepIds.isNotEmpty()) {
            val placeholders = keepIds.joinToString(",") { "?" }
            db.delete("operations", "state IN ($terminal) AND id NOT IN ($placeholders)", keepIds.toTypedArray())
        } else {
            db.delete("operations", "state IN ($terminal)", null)
        }
    }

    private fun decodeSafely(raw: String): FileOperation? = runCatching { OperationJsonCodec.decode(raw) }.getOrNull()

    companion object {
        private const val DATABASE_NAME = "step2_operations.db"
        private const val DATABASE_VERSION = 1
    }
}
