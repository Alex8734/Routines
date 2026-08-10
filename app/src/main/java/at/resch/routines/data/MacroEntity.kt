package at.resch.routines.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Schlankes DB-Schema (Home-Assistant-Stil): exakt zwei Spalten.
 * Alle weiteren Felder (name, enabled, trigger, actions) leben ausschließlich
 * im rohen JSON-String [configuration] und werden erst im Domain-Layer geparst.
 */
@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey
    @ColumnInfo(name = "macro_id")
    val macroId: String,

    /** Roher JSON-String des MacroScript — single source of truth. */
    @ColumnInfo(name = "configuration")
    val configuration: String
)
