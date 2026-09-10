package com.myrunningapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class MyRunningApp : Application(), WorkConfiguration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
    }

    /**
     * osmdroid needs a User-Agent (OSM's tile servers reject the default) and a
     * writable cache directory. One-time setup; the map screens (milestone 3)
     * just create MapViews.
     */
    private fun configureOsmdroid() {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        osmConfig.osmdroidBasePath = base
        osmConfig.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
    }
}
