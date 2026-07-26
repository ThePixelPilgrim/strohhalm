package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.RepoDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [RepoDao] so the repository can be tested without Room. */
class FakeRepoDao : RepoDao {

    private val rows = MutableStateFlow<List<Repo>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<Repo>> =
        rows.map { list -> list.sortedBy { it.displayName.lowercase() } }

    override fun observe(id: Long): Flow<Repo?> =
        rows.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun all(): List<Repo> = rows.value.sortedBy { it.lastSyncAt ?: 0L }

    override suspend fun byId(id: Long): Repo? = rows.value.firstOrNull { it.id == id }

    override suspend fun localPaths(): List<String> = rows.value.map { it.localPath }

    override suspend fun insert(repo: Repo): Long {
        val id = nextId++
        rows.value = rows.value + repo.copy(id = id)
        return id
    }

    override suspend fun update(repo: Repo) {
        rows.value = rows.value.map { if (it.id == repo.id) repo else it }
    }

    override suspend fun delete(repo: Repo) {
        rows.value = rows.value.filterNot { it.id == repo.id }
    }
}
