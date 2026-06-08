package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_apps ORDER BY timestamp DESC")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(app: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Database(entities = [BlockedApp::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
}

class BlockedAppRepository(private val dao: BlockedAppDao) {
    val allBlockedApps: Flow<List<BlockedApp>> = dao.getAllBlockedApps()

    suspend fun insert(app: BlockedApp) {
        dao.insertBlockedApp(app)
    }

    suspend fun deleteByPackage(packageName: String) {
        dao.deleteByPackage(packageName)
    }
}
