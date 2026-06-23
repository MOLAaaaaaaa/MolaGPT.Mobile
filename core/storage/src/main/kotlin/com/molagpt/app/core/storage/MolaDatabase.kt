package com.molagpt.app.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.molagpt.app.core.storage.dao.ConversationDao
import com.molagpt.app.core.storage.dao.ByokProviderDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.dao.StreamTaskDao
import com.molagpt.app.core.storage.entity.ByokProviderEntity
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.MessageEntity
import com.molagpt.app.core.storage.entity.StreamTaskEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, StreamTaskEntity::class, ByokProviderEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class MolaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun byokProviderDao(): ByokProviderDao
    abstract fun messageDao(): MessageDao
    abstract fun streamTaskDao(): StreamTaskDao

    companion object {
        fun build(context: Context): MolaDatabase =
            Room.databaseBuilder(context.applicationContext, MolaDatabase::class.java, "mola.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN placeholder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stream_tasks (
                        sessionId TEXT NOT NULL,
                        streamSessionId TEXT NOT NULL,
                        conversationId TEXT NOT NULL,
                        assistantMessageId TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        modelDisplayName TEXT,
                        apiUrl TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(sessionId)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_deletedAt_pinned_updatedAt ON conversations(deletedAt, pinned, updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_placeholder ON conversations(placeholder)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN visibleInList INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE conversations
                    SET messageCount = (
                            SELECT COUNT(*)
                            FROM messages
                            WHERE messages.sessionId = conversations.sessionId
                        ),
                        visibleInList = CASE
                            WHEN placeholder = 1
                              OR (
                                  SELECT COUNT(*)
                                  FROM messages
                                  WHERE messages.sessionId = conversations.sessionId
                              ) > 0 THEN 1
                            ELSE 0
                        END
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_deletedAt_visibleInList_pinned_updatedAt ON conversations(deletedAt, visibleInList, pinned, updatedAt)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN providerId TEXT DEFAULT 'molagpt'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN providerKind TEXT NOT NULL DEFAULT 'MOLAGPT'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS byok_providers (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        baseUrl TEXT NOT NULL,
                        chatPath TEXT NOT NULL,
                        modelsPath TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        modelsJson TEXT NOT NULL DEFAULT '[]',
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stream_tasks ADD COLUMN providerId TEXT DEFAULT 'molagpt'")
                db.execSQL("ALTER TABLE stream_tasks ADD COLUMN providerKind TEXT NOT NULL DEFAULT 'MOLAGPT'")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE byok_providers ADD COLUMN imagePath TEXT NOT NULL DEFAULT 'v1/images/generations'")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE byok_providers ADD COLUMN purpose TEXT NOT NULL DEFAULT 'CHAT'")
                db.execSQL("ALTER TABLE byok_providers ADD COLUMN imageFormat TEXT NOT NULL DEFAULT 'OPENAI_IMAGES'")
                db.execSQL("ALTER TABLE byok_providers ADD COLUMN imageEditPath TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
