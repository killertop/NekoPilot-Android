package io.nekohasekai.sagernet.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 9,
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(*ALL_MIGRATIONS)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(Dispatchers.IO.asExecutor())
                .build()
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE `proxy_groups_v2` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                        "`userOrder` INTEGER NOT NULL," +
                        "`ungrouped` INTEGER NOT NULL," +
                        "`name` TEXT," +
                        "`type` INTEGER NOT NULL," +
                        "`subscription` BLOB," +
                        "`order` INTEGER NOT NULL," +
                        "`isSelector` INTEGER NOT NULL," +
                        "`frontProxy` INTEGER NOT NULL," +
                        "`landingProxy` INTEGER NOT NULL)"
                )
                database.execSQL(
                    "INSERT INTO `proxy_groups_v2` " +
                        "(`id`,`userOrder`,`ungrouped`,`name`,`type`,`subscription`,`order`," +
                        "`isSelector`,`frontProxy`,`landingProxy`) " +
                        "SELECT `id`,`userOrder`,`ungrouped`,`name`,`type`,`subscription`,`order`," +
                        "0,-1,-1 FROM `proxy_groups`"
                )
                database.execSQL("DROP TABLE `proxy_groups`")
                database.execSQL("ALTER TABLE `proxy_groups_v2` RENAME TO `proxy_groups`")
                database.execSQL(
                    "ALTER TABLE `proxy_entities` ADD COLUMN `shadowTLSBean` BLOB"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `mieruBean` BLOB")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) = Unit
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `proxy_entities` ADD COLUMN `anyTLSBean` BLOB DEFAULT NULL"
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rules` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS `groupId`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proxy_entities_groupId_userOrder` " +
                        "ON `proxy_entities` (`groupId`, `userOrder`)"
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE `proxy_entities_v8` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                        "`groupId` INTEGER NOT NULL," +
                        "`type` INTEGER NOT NULL," +
                        "`userOrder` INTEGER NOT NULL," +
                        "`status` INTEGER NOT NULL," +
                        "`ping` INTEGER NOT NULL," +
                        "`uuid` TEXT NOT NULL," +
                        "`error` TEXT," +
                        "`socksBean` BLOB," +
                        "`httpBean` BLOB," +
                        "`ssBean` BLOB," +
                        "`vmessBean` BLOB," +
                        "`trojanBean` BLOB," +
                        "`trojanGoBean` BLOB," +
                        "`mieruBean` BLOB," +
                        "`naiveBean` BLOB," +
                        "`hysteriaBean` BLOB," +
                        "`tuicBean` BLOB," +
                        "`sshBean` BLOB," +
                        "`wgBean` BLOB," +
                        "`shadowTLSBean` BLOB," +
                        "`anyTLSBean` BLOB," +
                        "`chainBean` BLOB," +
                        "`nekoBean` BLOB," +
                        "`configBean` BLOB)"
                )
                database.execSQL(
                    "INSERT INTO `proxy_entities_v8` (" +
                        "`id`,`groupId`,`type`,`userOrder`,`status`,`ping`,`uuid`,`error`," +
                        "`socksBean`,`httpBean`,`ssBean`,`vmessBean`,`trojanBean`,`trojanGoBean`," +
                        "`mieruBean`,`naiveBean`,`hysteriaBean`,`tuicBean`,`sshBean`,`wgBean`," +
                        "`shadowTLSBean`,`anyTLSBean`,`chainBean`,`nekoBean`,`configBean`) " +
                        "SELECT `id`,`groupId`,`type`,`userOrder`,`status`,`ping`,`uuid`,`error`," +
                        "`socksBean`,`httpBean`,`ssBean`,`vmessBean`,`trojanBean`,`trojanGoBean`," +
                        "`mieruBean`,`naiveBean`,`hysteriaBean`,`tuicBean`,`sshBean`,`wgBean`," +
                        "`shadowTLSBean`,`anyTLSBean`,`chainBean`,`nekoBean`,`configBean` " +
                        "FROM `proxy_entities`"
                )
                database.execSQL("DROP TABLE `proxy_entities`")
                database.execSQL("ALTER TABLE `proxy_entities_v8` RENAME TO `proxy_entities`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proxy_entities_groupId_userOrder` " +
                        "ON `proxy_entities` (`groupId`, `userOrder`)"
                )
            }
        }

        // Version 8 temporarily removed the historical traffic columns. Keep the
        // values for existing v7 users while retaining the no-polling runtime design.
        internal val MIGRATION_7_9 = object : Migration(7, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE `proxy_entities_v9` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                        "`groupId` INTEGER NOT NULL," +
                        "`type` INTEGER NOT NULL," +
                        "`userOrder` INTEGER NOT NULL," +
                        "`tx` INTEGER NOT NULL DEFAULT 0," +
                        "`rx` INTEGER NOT NULL DEFAULT 0," +
                        "`status` INTEGER NOT NULL," +
                        "`ping` INTEGER NOT NULL," +
                        "`uuid` TEXT NOT NULL," +
                        "`error` TEXT," +
                        "`socksBean` BLOB," +
                        "`httpBean` BLOB," +
                        "`ssBean` BLOB," +
                        "`vmessBean` BLOB," +
                        "`trojanBean` BLOB," +
                        "`trojanGoBean` BLOB," +
                        "`mieruBean` BLOB," +
                        "`naiveBean` BLOB," +
                        "`hysteriaBean` BLOB," +
                        "`tuicBean` BLOB," +
                        "`sshBean` BLOB," +
                        "`wgBean` BLOB," +
                        "`shadowTLSBean` BLOB," +
                        "`anyTLSBean` BLOB," +
                        "`chainBean` BLOB," +
                        "`nekoBean` BLOB," +
                        "`configBean` BLOB)"
                )
                database.execSQL(
                    "INSERT INTO `proxy_entities_v9` (" +
                        "`id`,`groupId`,`type`,`userOrder`,`tx`,`rx`,`status`,`ping`,`uuid`,`error`," +
                        "`socksBean`,`httpBean`,`ssBean`,`vmessBean`,`trojanBean`,`trojanGoBean`," +
                        "`mieruBean`,`naiveBean`,`hysteriaBean`,`tuicBean`,`sshBean`,`wgBean`," +
                        "`shadowTLSBean`,`anyTLSBean`,`chainBean`,`nekoBean`,`configBean`) " +
                        "SELECT `id`,`groupId`,`type`,`userOrder`,`tx`,`rx`,`status`,`ping`,`uuid`,`error`," +
                        "`socksBean`,`httpBean`,`ssBean`,`vmessBean`,`trojanBean`,`trojanGoBean`," +
                        "`mieruBean`,`naiveBean`,`hysteriaBean`,`tuicBean`,`sshBean`,`wgBean`," +
                        "`shadowTLSBean`,`anyTLSBean`,`chainBean`,`nekoBean`,`configBean` " +
                        "FROM `proxy_entities`"
                )
                database.execSQL("DROP TABLE `proxy_entities`")
                database.execSQL("ALTER TABLE `proxy_entities_v9` RENAME TO `proxy_entities`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proxy_entities_groupId_userOrder` " +
                        "ON `proxy_entities` (`groupId`, `userOrder`)"
                )
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `proxy_entities` ADD COLUMN `tx` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `proxy_entities` ADD COLUMN `rx` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_7_9,
            MIGRATION_8_9,
        )

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
