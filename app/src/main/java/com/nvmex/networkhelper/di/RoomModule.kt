package com.nvmex.networkhelper.di

import android.content.Context
import androidx.room.Room
import com.nvmex.networkhelper.db.AppDatabase
import com.nvmex.networkhelper.db.dao.LteCellParamDao
import com.nvmex.networkhelper.db.dao.NrCellParamDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RoomModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "network_helper.db"
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
    }

    @Provides
    fun provideNrCellParamDao(db: AppDatabase): NrCellParamDao = db.nrCellParamDao()

    @Provides
    fun provideLteCellParamDao(db: AppDatabase): LteCellParamDao = db.lteCellParamDao()

    @Provides
    fun provideManagedCellDao(db: AppDatabase): com.nvmex.networkhelper.db.entity.ManagedCellDao = db.managedCellDao()
}
