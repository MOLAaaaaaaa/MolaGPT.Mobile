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
import com.molagpt.app.core.storage.dao.PersonaDao
import com.molagpt.app.core.storage.dao.StreamTaskDao
import com.molagpt.app.core.storage.entity.ByokProviderEntity
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.MessageEntity
import com.molagpt.app.core.storage.entity.PersonaEntity
import com.molagpt.app.core.storage.entity.StreamTaskEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, StreamTaskEntity::class, ByokProviderEntity::class, PersonaEntity::class],
    version = 11,
    exportSchema = false,
)
abstract class MolaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun byokProviderDao(): ByokProviderDao
    abstract fun messageDao(): MessageDao
    abstract fun streamTaskDao(): StreamTaskDao
    abstract fun personaDao(): PersonaDao

    companion object {
        fun build(context: Context): MolaDatabase =
            Room.databaseBuilder(context.applicationContext, MolaDatabase::class.java, "mola.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 会话挂角色绑定 + 会话级提示词（systemPrompt/mode 预留）。
                db.execSQL("ALTER TABLE conversations ADD COLUMN personaId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN systemPrompt TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN systemPromptMode TEXT")
                // 角色表（仅 BYOK 使用）。index 名须与 PersonaEntity 的 @Index 一致，否则 Room schema 校验失败。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personas (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        icon TEXT,
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        defaultEnableNetwork INTEGER,
                        defaultEnableWebFetch INTEGER,
                        defaultThinking INTEGER,
                        defaultReasoningEffort TEXT,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        isBuiltin INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deletedAt INTEGER,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personas_deletedAt_pinned_sortOrder ON personas(deletedAt, pinned, sortOrder)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 防御历史异常行：BYOK 附件曾可能把 data:base64 写进单条消息 JSON，
                // 导致 SELECT * 填充 CursorWindow 时抛 SQLiteBlobTooBigException。
                db.execSQL(
                    """
                    UPDATE messages
                    SET fragmentsJson = '[]',
                        metadataJson = '{}',
                        rawText = CASE
                            WHEN rawText IS NULL THEN NULL
                            ELSE substr(rawText, 1, 20000)
                        END
                    WHERE (
                        length(fragmentsJson) +
                        length(metadataJson) +
                        coalesce(length(rawText), 0)
                    ) > 1000000
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // BYOK 自定义请求头（参数覆写）。默认 '[]' 使旧行保持无附加头。
                db.execSQL("ALTER TABLE byok_providers ADD COLUMN customHeadersJson TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}
