package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IncidentDao {
    @Query("SELECT * FROM incidencias ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidencias WHERE estadoSincronizacion != 'SINCRONIZADO' ORDER BY timestamp ASC")
    suspend fun getPendientes(): List<IncidentEntity>

    @Query("SELECT COUNT(*) FROM incidencias WHERE estadoSincronizacion != 'SINCRONIZADO'")
    fun getPendientesCountFlow(): Flow<Int>

    @Query("SELECT * FROM incidencias WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): IncidentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: IncidentEntity)

    @Update
    suspend fun update(entity: IncidentEntity)

    @Query("DELETE FROM incidencias WHERE id = :id")
    suspend fun delete(id: String)
}
