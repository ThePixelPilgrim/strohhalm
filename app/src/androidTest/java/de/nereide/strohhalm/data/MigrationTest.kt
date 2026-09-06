package de.nereide.strohhalm.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens a version-1 database with a repository row, migrates to 2, and
 * checks the row survived and the new table exists. Needs a device; never
 * marked done on the strength of the JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StrohhalmDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2KeepsReposAndAddsSyncEvents() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO repos (displayName, remoteUrl, localPath, lastStatus, sizeBytes, refCount, createdAt)
                VALUES ('Alpha', 'ssh://git@host/srv/alpha.git', '/storage/emulated/0/Strohhalm/alpha.git', 'NEVER', 0, 0, 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(name, 2, true)

        db.query("SELECT COUNT(*) FROM repos").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sync_events").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }
}
