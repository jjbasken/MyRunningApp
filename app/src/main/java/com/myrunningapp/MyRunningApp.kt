package com.myrunningapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import com.myrunningapp.data.health.HealthSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class MyRunningApp : Application(), WorkConfiguration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var healthSyncScheduler: HealthSyncScheduler

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
        // Nothing else would drain a queue that was filled without the app
        // running: RECOVER_INTERRUPTED_RUNS queues a run salvaged after a
        // process death as the database opens, and no finish, edit or delete
        // follows it. Cheap either way — the engine returns immediately while
        // the toggle is off, and the work is unique-named, so this collapses
        // into whatever is already queued.
        healthSyncScheduler.requestSync()
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
