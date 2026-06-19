package messina

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import messina.settings.getApplicationPath
import kotlinx.io.files.Path

object Database {
    private val connection: SQLiteConnection

    init {
        val driver = BundledSQLiteDriver()
        val path = getApplicationPath() + "/messina.sqlite"
        // kotlinx.io.files.SystemFileSystem.delete(Path(path), false)
        connection = driver.open(
            path,
            SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_CREATE or SQLITE_OPEN_READWRITE
        )

        val version = this.execute("PRAGMA user_version") { rows ->
            rows.next()!!.getLong(0).toInt()
        }

        if (version == 0) {
            connection.execSQL("PRAGMA user_version = 1")
            connection.execSQL("CREATE TABLE storage (key TEXT PRIMARY KEY, value TEXT NON NULL)")
            connection.execSQL(
                "CREATE TABLE sensors (" +
                        "identifier BLOB PRIMARY KEY," +
                        "sensor TEXT NON NULL)"
            )
            connection.execSQL(
                "CREATE TABLE glucose_history (" +
                        "sensor_id INTEGER," +
                        "time INTEGER," +
                        "glucose REAL NON NULL," +
                        "PRIMARY KEY(sensor_id, time))"
            )
            connection.execSQL(
                "CREATE TABLE event_log (" +
                        "sensor_id INTEGER NON NULL," +
                        "time INTEGER NON NULL," +
                        "payload TEXT NON NULL)"
            )
            connection.execSQL(
                "CREATE INDEX event_log_sensor_time ON event_log (sensor_id, time)"
            )
        }
    }

    // Allows for block-less execution since a default block can't be set in the generic function
    fun execute(sql: String, args: Array<Any?>? = null) {
        execute(sql, args) { it.step() }
    }

    fun <T> execute(
        sql: String,
        args: Array<Any?>? = null,
        block: (DatabaseStatement) -> T
    ): T {
        return connection.prepare(sql).use { stmt ->
            bindArgs(stmt, args)
            block(DatabaseStatement(stmt))
        }
    }

    fun transact(sql: String, args: List<Array<Any?>>) {
        connection.execSQL("BEGIN TRANSACTION")
        try {
            connection.prepare(sql).use { stmt ->
                for (row in args) {
                    stmt.reset()
                    bindArgs(stmt, row)
                    stmt.step()
                }
            }
            connection.execSQL("COMMIT")
        } catch (e: Exception) {
            connection.execSQL("ROLLBACK")
            throw e
        }
    }


}

private fun bindArgs(stmt: SQLiteStatement, args: Array<Any?>?) {
    args?.forEachIndexed { index, arg ->
        val i = index + 1
        when (arg) {
            null -> stmt.bindNull(i)
            is String -> stmt.bindText(i, arg)
            is Int -> stmt.bindLong(i, arg.toLong())
            is Long -> stmt.bindLong(i, arg)
            is UInt -> stmt.bindLong(i, arg.toLong())
            is ULong -> stmt.bindLong(i, arg.toLong())
            is Double -> stmt.bindDouble(i, arg)
            is Float -> stmt.bindDouble(i, arg.toDouble())
            is Boolean -> stmt.bindLong(i, if (arg) 1L else 0L)
            is ByteArray -> stmt.bindBlob(i, arg)
            else -> error("Unsupported bind argument type: ${arg::class.simpleName}")
        }
    }
}

// Wrap the statement to get better access ergonomics
class DatabaseStatement(private val stmt: SQLiteStatement) : Iterable<DatabaseStatement> {
    private val columnIndices: Map<String, Int> by lazy {
        (0 until stmt.getColumnCount()).associate { stmt.getColumnName(it) to it }
    }

    fun step() = stmt.step()

    fun getLong(index: Int) = stmt.getLong(index)
    fun getText(index: Int) = stmt.getText(index)
    fun getDouble(index: Int) = stmt.getDouble(index)
    fun getBlob(index: Int) = stmt.getBlob(index)
    fun isNull(index: Int) = stmt.isNull(index)

    fun getLong(name: String) = getLong(columnIndices.getValue(name))
    fun getText(name: String) = getText(columnIndices.getValue(name))
    fun getDouble(name: String) = getDouble(columnIndices.getValue(name))
    fun getBlob(name: String) = getBlob(columnIndices.getValue(name))
    fun isNull(name: String) = isNull(columnIndices.getValue(name))

    fun next(): DatabaseStatement? = if (stmt.step()) this else null

    override fun iterator(): Iterator<DatabaseStatement> = object : Iterator<DatabaseStatement> {
        private var hasNext: Boolean? = null

        override fun hasNext(): Boolean {
            if (hasNext == null) hasNext = stmt.step()
            return hasNext!!
        }

        override fun next(): DatabaseStatement {
            hasNext = null
            return this@DatabaseStatement
        }
    }
}
