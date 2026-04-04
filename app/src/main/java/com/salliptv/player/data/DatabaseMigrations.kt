package com.salliptv.player.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations de base de données
 */
object DatabaseMigrations {

    /**
     * Migration 2 -> 3
     * Ajoute les champs pour le nettoyage et regroupement intelligent des chaînes
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Ajout des nouvelles colonnes pour ChannelNameCleaner
            database.execSQL("ALTER TABLE channels ADD COLUMN cleanName TEXT")
            database.execSQL("ALTER TABLE channels ADD COLUMN qualityBadge TEXT")
            database.execSQL("ALTER TABLE channels ADD COLUMN countryPrefix TEXT")
            database.execSQL("ALTER TABLE channels ADD COLUMN codecInfo TEXT")
            database.execSQL("ALTER TABLE channels ADD COLUMN groupId TEXT")
            
            // Création des index pour performance
            database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_cleanName ON channels(cleanName)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_groupId ON channels(groupId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_composite ON channels(playlistId, type, cleanName)")
            
            // Migration des données existantes: initialiser cleanName avec name
            database.execSQL("UPDATE channels SET cleanName = name WHERE cleanName IS NULL")
            
            // Initialiser groupId avec une valeur basée sur cleanName
            database.execSQL("""
                UPDATE channels 
                SET groupId = LOWER(COALESCE(countryPrefix, '') || '_' || COALESCE(cleanName, name))
                WHERE groupId IS NULL
            """)
        }
    }

    /**
     * Migration 1 -> 2 (existante - pour référence)
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Migration existante si nécessaire
        }
    }

    /**
     * Migration 3 -> 4
     * Ajoute le champ hidden pour le Smart Filter
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE channels ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Performance indexes for GROUP BY queries on 549K+ rows
            database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_playlistId_type_groupTitle_hidden ON channels (playlistId, type, groupTitle, hidden)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_channels_playlistId_type_hidden ON channels (playlistId, type, hidden)")
        }
    }
}
