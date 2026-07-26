package de.nereide.strohhalm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {

    @Query("SELECT * FROM repos ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Repo>>

    @Query("SELECT * FROM repos WHERE id = :id")
    fun observe(id: Long): Flow<Repo?>

    /** Oldest-synced first, so a long backlog drains fairly. */
    @Query("SELECT * FROM repos ORDER BY IFNULL(lastSyncAt, 0) ASC")
    suspend fun all(): List<Repo>

    @Query("SELECT * FROM repos WHERE id = :id")
    suspend fun byId(id: Long): Repo?

    @Query("SELECT localPath FROM repos")
    suspend fun localPaths(): List<String>

    @Insert
    suspend fun insert(repo: Repo): Long

    @Update
    suspend fun update(repo: Repo)

    @Delete
    suspend fun delete(repo: Repo)
}
