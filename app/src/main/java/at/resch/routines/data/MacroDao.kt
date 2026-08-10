package at.resch.routines.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reines Roh-Zugriffs-DAO. Es kennt keine Makro-Semantik — `enabled` etc. leben
 * im JSON und werden im Domain-Layer ausgewertet. Daher kein WHERE auf Inhalt.
 */
@Dao
interface MacroDao {

    @Query("SELECT * FROM macros")
    fun observeAll(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros WHERE macro_id = :id")
    suspend fun getById(id: String): MacroEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(macro: MacroEntity)

    @Query("DELETE FROM macros WHERE macro_id = :id")
    suspend fun deleteById(id: String)
}
