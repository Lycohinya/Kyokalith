package com.tinyyana.kyokalith.db

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KyokalithDatabaseMigrationTest {
    @Test
    fun `legacy schema initializes without changing salt`() = withDatabase { db, url ->
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                it.executeUpdate("INSERT INTO meta VALUES ('salt', 'legacy-salt')")
                it.executeUpdate("INSERT INTO meta VALUES ('schema_version', '1')")
            }
        }

        db.init()

        assertEquals("legacy-salt", db.getMeta("salt"))
        assertEquals(KyokalithDatabase.VEIN_ALGORITHM_VERSION, db.getMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY))
        assertEquals(0, count(url, "materialized_positions"))
    }

    @Test
    fun `version two algorithm invalidates only materialized positions`() = withDatabase { db, url ->
        db.init()
        db.setMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY, "2")
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate("INSERT INTO chunk_epoch VALUES ('world', 1, 2, 3)")
                it.executeUpdate("INSERT INTO dirty_positions VALUES ('world', 1, 2, 3, X'0102')")
                it.executeUpdate(
                    "INSERT INTO eligible_placed_ores VALUES " +
                        "('world', 1, 2, 3, 3, 'diamond', 'DIAMOND_ORE', 'token', 'player', 1)",
                )
                it.executeUpdate("INSERT INTO suspended_chunks VALUES ('world', 1, 2, 'test', 1)")
                it.executeUpdate(
                    "INSERT INTO materialized_positions VALUES " +
                        "('world', 1, 2, 3, 1, 2, 3, 'diamond', 'legacy-vein', 'DIAMOND_ORE')",
                )
            }
        }
        val salt = db.getMeta("salt")

        db.init()

        assertEquals(salt, db.getMeta("salt"))
        assertEquals("1", db.getMeta("schema_version"))
        assertEquals(1, count(url, "chunk_epoch"))
        assertEquals(1, count(url, "dirty_positions"))
        assertEquals(1, count(url, "eligible_placed_ores"))
        assertEquals(1, count(url, "suspended_chunks"))
        assertEquals(0, count(url, "materialized_positions"))
    }

    @Test
    fun `current algorithm init is idempotent`() = withDatabase { db, url ->
        db.init()
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO materialized_positions VALUES " +
                        "('world', 0, 0, 0, 1, 2, 3, 'iron', 'current-vein', 'IRON_ORE')",
                )
            }
        }

        db.init()

        assertEquals(1, count(url, "materialized_positions"))
    }

    @Test
    fun `failed invalidation rolls back rows and algorithm marker together`() = withDatabase { db, url ->
        db.init()
        db.setMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY, "2")
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO materialized_positions VALUES " +
                        "('world', 0, 0, 0, 1, 2, 3, 'iron', 'legacy-vein', 'IRON_ORE')",
                )
                it.executeUpdate(
                    "CREATE TRIGGER reject_lock_clear BEFORE DELETE ON materialized_positions " +
                        "BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
                )
            }
        }

        assertFails { db.init() }

        assertEquals(1, count(url, "materialized_positions"))
        assertEquals("2", db.getMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY))
    }

    private fun count(url: String, table: String): Int =
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun withDatabase(block: (KyokalithDatabase, String) -> Unit) {
        val file = createTempFile(suffix = ".db")
        try {
            block(KyokalithDatabase(file.toFile()), "jdbc:sqlite:${file.toFile().absolutePath}")
        } finally {
            file.deleteIfExists()
        }
    }

    /**
     * 回歸測試(2026-08-29):keep-alive 連線的第一版只呼叫 getConnection,沒有在上面跑任何
     * 語句——而 sqlite-jdbc 是延遲開檔的,那條連線根本沒碰到資料庫檔案,擋不住
     * 「最後一條連線關閉時 checkpoint 整個 WAL」,線上量到的 commit 時間完全沒變。
     * 判準用 -wal 檔存不存在:它只在真的有連線開著時才在磁碟上。
     */
    @Test
    fun `keep-alive connection actually holds the database open and close releases it`() {
        val file = createTempFile(suffix = ".db")
        val wal = File(file.toFile().path + "-wal")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            assertFalse(wal.isFile, "還沒開 keep-alive 時不該有 WAL 檔")

            db.openKeepAliveConnection()
            assertTrue(wal.isFile, "keep-alive 必須真的開啟資料庫檔案,否則擋不住 WAL checkpoint")

            db.close()
            assertFalse(wal.isFile, "close 之後必須放掉檔案,不然 PlugMan 熱插拔會卡住 SQLite 檔")
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `opening the keep-alive twice does not leak a second connection`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            db.openKeepAliveConnection()
            val after = db.connectionsOpened

            db.openKeepAliveConnection()

            assertEquals(after, db.connectionsOpened)
            db.close()
        } finally {
            file.deleteIfExists()
        }
    }
}
