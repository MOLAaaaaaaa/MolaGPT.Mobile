package com.molagpt.app.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.molagpt.app.core.storage.dao.ByokMemoryDao
import com.molagpt.app.core.storage.dao.ConversationDao
import com.molagpt.app.core.storage.dao.ByokProviderDao
import com.molagpt.app.core.storage.dao.LorebookDao
import com.molagpt.app.core.storage.dao.MessageDao
import com.molagpt.app.core.storage.dao.PersonaDao
import com.molagpt.app.core.storage.dao.StreamTaskDao
import com.molagpt.app.core.storage.entity.ByokMemoryCandidateEntity
import com.molagpt.app.core.storage.entity.ByokMemoryEntryEntity
import com.molagpt.app.core.storage.entity.ByokMemoryEvidenceEntity
import com.molagpt.app.core.storage.entity.ByokMemorySuppressionEntity
import com.molagpt.app.core.storage.entity.ByokMemoryTopicEntity
import com.molagpt.app.core.storage.entity.ByokProviderEntity
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.LorebookEntity
import com.molagpt.app.core.storage.entity.MessageEntity
import com.molagpt.app.core.storage.entity.PersonaEntity
import com.molagpt.app.core.storage.entity.StreamTaskEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        StreamTaskEntity::class,
        ByokProviderEntity::class,
        PersonaEntity::class,
        ByokMemoryEntryEntity::class,
        ByokMemoryEvidenceEntity::class,
        ByokMemoryCandidateEntity::class,
        ByokMemorySuppressionEntity::class,
        ByokMemoryTopicEntity::class,
        LorebookEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
abstract class MolaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun byokProviderDao(): ByokProviderDao
    abstract fun messageDao(): MessageDao
    abstract fun streamTaskDao(): StreamTaskDao
    abstract fun personaDao(): PersonaDao
    abstract fun lorebookDao(): LorebookDao
    abstract fun byokMemoryDao(): ByokMemoryDao

    companion object {
        fun build(context: Context): MolaDatabase =
            Room.databaseBuilder(context.applicationContext, MolaDatabase::class.java, "mola.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
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

        /**
         * BYOK 本地记忆。五张新表 + 会话级两个覆盖开关。
         *
         * 新表一律不写 SQL DEFAULT：它们是空表，没有旧行要回填，而 Room 的 TableInfo 校验
         * 会把 migration 里多出来的 DEFAULT 判为 schema 不一致、开库即崩。
         * 会话两列是可空 INTEGER：null 表示跟随全局开关，显式 0/1 才是本会话覆盖。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS byok_memory_entries (
                        id TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        normalizedKey TEXT NOT NULL,
                        section TEXT NOT NULL,
                        category TEXT,
                        profileKey TEXT,
                        confidence REAL NOT NULL,
                        halfLifeDays REAL,
                        expiresAt INTEGER,
                        permanent INTEGER NOT NULL,
                        origin TEXT NOT NULL,
                        recurrence INTEGER NOT NULL,
                        firstObservedAt INTEGER NOT NULL,
                        lastReinforcedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_byok_memory_entries_scope_normalizedKey " +
                        "ON byok_memory_entries (scope, normalizedKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_byok_memory_entries_scope_section " +
                        "ON byok_memory_entries (scope, section)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS byok_memory_evidence (
                        id TEXT NOT NULL,
                        entryId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        quote TEXT NOT NULL,
                        observedAt INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(entryId) REFERENCES byok_memory_entries(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_byok_memory_evidence_entryId ON byok_memory_evidence (entryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_byok_memory_evidence_messageId ON byok_memory_evidence (messageId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS byok_memory_candidates (
                        id TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        normalizedKey TEXT NOT NULL,
                        section TEXT NOT NULL,
                        category TEXT,
                        profileKey TEXT,
                        confidence REAL NOT NULL,
                        sourceSessionId TEXT NOT NULL,
                        sourceMessageId TEXT NOT NULL,
                        quote TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_byok_memory_candidates_scope_normalizedKey " +
                        "ON byok_memory_candidates (scope, normalizedKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_byok_memory_candidates_createdAt " +
                        "ON byok_memory_candidates (createdAt)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS byok_memory_suppressions (
                        scope TEXT NOT NULL,
                        normalizedKey TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(scope, normalizedKey)
                    )
                    """.trimIndent(),
                )

                db.execSQL("ALTER TABLE conversations ADD COLUMN byokMemoryEnabled INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN byokConversationRecallEnabled INTEGER")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 记忆整理水位线：createdAt 大于它的消息才是「还没整理过的」。
                // 0 = 从未整理，此时首窗会被截到最后若干条，避免在老会话上第一次整理就把整段历史发出去。
                db.execSQL("ALTER TABLE conversations ADD COLUMN byokMemoryWatermarkAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 早期实现允许同一个画像字段出现多条。保留用户维护、置信度更高且更新更近的一条。
                db.execSQL(
                    """
                    DELETE FROM byok_memory_entries
                    WHERE byok_memory_entries.profileKey IS NOT NULL
                      AND EXISTS (
                          SELECT 1
                          FROM byok_memory_entries AS better
                          WHERE better.scope = byok_memory_entries.scope
                            AND better.profileKey = byok_memory_entries.profileKey
                            AND (
                                CASE better.origin
                                    WHEN 'manual' THEN 4
                                    WHEN 'confirmed' THEN 3
                                    WHEN 'tool' THEN 2
                                    ELSE 1
                                END > CASE byok_memory_entries.origin
                                    WHEN 'manual' THEN 4
                                    WHEN 'confirmed' THEN 3
                                    WHEN 'tool' THEN 2
                                    ELSE 1
                                END
                                OR (
                                    CASE better.origin
                                        WHEN 'manual' THEN 4
                                        WHEN 'confirmed' THEN 3
                                        WHEN 'tool' THEN 2
                                        ELSE 1
                                    END = CASE byok_memory_entries.origin
                                        WHEN 'manual' THEN 4
                                        WHEN 'confirmed' THEN 3
                                        WHEN 'tool' THEN 2
                                        ELSE 1
                                    END
                                    AND (
                                        better.confidence > byok_memory_entries.confidence
                                        OR (better.confidence = byok_memory_entries.confidence AND better.updatedAt > byok_memory_entries.updatedAt)
                                        OR (better.confidence = byok_memory_entries.confidence AND better.updatedAt = byok_memory_entries.updatedAt AND better.id > byok_memory_entries.id)
                                    )
                                )
                            )
                      )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_byok_memory_entries_scope_profileKey " +
                        "ON byok_memory_entries(scope, profileKey)",
                )
            }
        }

        /** 角色卡（酒馆）：角色挂卡片资料与头像，另起一张共享世界书表。 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personas ADD COLUMN profileJson TEXT")
                db.execSQL("ALTER TABLE personas ADD COLUMN avatarPath TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lorebooks (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        bookJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deletedAt INTEGER,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lorebooks_deletedAt_updatedAt ON lorebooks(deletedAt, updatedAt)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE byok_memory_entries ADD COLUMN topicId TEXT")
                db.execSQL("ALTER TABLE byok_memory_candidates ADD COLUMN topicId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_byok_memory_entries_scope_topicId " +
                        "ON byok_memory_entries(scope, topicId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS byok_memory_topics (
                        id TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        normalizedKey TEXT NOT NULL,
                        groupName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_byok_memory_topics_scope_normalizedKey " +
                        "ON byok_memory_topics(scope, normalizedKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_byok_memory_topics_scope_groupName " +
                        "ON byok_memory_topics(scope, groupName)",
                )
            }
        }
    }
}
