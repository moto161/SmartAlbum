package com.example.smartalbum.di

import android.content.Context
import com.example.smartalbum.data.PhotoRepository
import com.example.smartalbum.db.PhotoDatabaseHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt依赖注入Module
 * 提供应用级别的单例依赖
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePhotoDatabaseHelper(
        @ApplicationContext context: Context
    ): PhotoDatabaseHelper {
        return PhotoDatabaseHelper.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePhotoRepository(
        @ApplicationContext context: Context
    ): PhotoRepository {
        return PhotoRepository(context)
    }
}
