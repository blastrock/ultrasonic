package org.moire.ultrasonic.app

import android.content.Context
import androidx.multidex.MultiDexApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.moire.ultrasonic.BuildConfig
import org.moire.ultrasonic.di.appPermanentStorage
import org.moire.ultrasonic.di.applicationModule
import org.moire.ultrasonic.di.baseNetworkModule
import org.moire.ultrasonic.di.mediaPlayerModule
import org.moire.ultrasonic.di.musicServiceModule
import org.moire.ultrasonic.log.FileLoggerTree
import org.moire.ultrasonic.log.TimberKoinLogger
import org.moire.ultrasonic.util.Settings
import timber.log.Timber
import timber.log.Timber.DebugTree

/**
 * The Main class of the Application
 */

class UApp : MultiDexApplication() {

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
        if (Settings.debugLogToFile) {
            FileLoggerTree.plantToTimberForest()
        }

        startKoin {
            // TODO Currently there is a bug in Koin which makes necessary to set the loglevel to ERROR
            logger(TimberKoinLogger(Level.ERROR))
            // logger(TimberKoinLogger(Level.INFO))

            // declare Android context
            androidContext(this@UApp)
            // declare modules to use
            modules(
                applicationModule,
                appPermanentStorage,
                baseNetworkModule,
                musicServiceModule,
                mediaPlayerModule
            )
        }
    }

    companion object {
        private var instance: UApp? = null

        fun applicationContext(): Context {
            return instance!!.applicationContext
        }
    }
}
