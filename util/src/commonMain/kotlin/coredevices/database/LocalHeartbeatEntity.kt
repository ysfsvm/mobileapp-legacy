package coredevices.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Watch analytics heartbeats kept on the phone for the local Battery Life page, when the app has
 * no analytics server to send them to.
 */
@Entity(
    tableName = "local_heartbeats",
    indices = [Index("receivedAt")],
)
data class LocalHeartbeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serial: String,
    val fwVersion: String?,
    val tzOffsetMinutes: Int,
    val receivedAt: Long,
    val payload: ByteArray,
)

@Dao
interface LocalHeartbeatDao {
    @Insert
    suspend fun insert(row: LocalHeartbeatEntity): Long

    @Query("DELETE FROM local_heartbeats WHERE receivedAt < :cutoff")
    suspend fun deleteReceivedBefore(cutoff: Long)

    @Query("SELECT * FROM local_heartbeats ORDER BY receivedAt ASC, id ASC")
    fun observeAll(): Flow<List<LocalHeartbeatEntity>>

    @Query("SELECT * FROM local_heartbeats ORDER BY receivedAt ASC, id ASC")
    suspend fun getAll(): List<LocalHeartbeatEntity>
}
