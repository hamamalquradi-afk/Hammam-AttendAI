package com.hammam.attendai.data.local

import androidx.room.migration.Migration

/** Fails closed if a release is configured without a complete sequential migration path. */
object DatabaseMigrationGuard{
    fun requireCompletePath(currentVersion:Int,migrations:Array<out Migration>){
        require(currentVersion>=1){"INVALID_DATABASE_VERSION"}
        val edges=migrations.groupBy{it.startVersion to it.endVersion}
        for(version in 1 until currentVersion){
            require(edges[version to (version+1)]?.size==1){"MISSING_OR_DUPLICATE_MIGRATION_${version}_${version+1}"}
        }
    }
}
