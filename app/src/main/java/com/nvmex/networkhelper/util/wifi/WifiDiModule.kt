package com.nvmex.networkhelper.util.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.nvmex.networkhelper.util.shell.DefaultShellRunner
import com.nvmex.networkhelper.util.shell.ShellRunner
import com.nvmex.networkhelper.util.shell.SuShellRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WifiDiModule {

    @Provides
    @Singleton
    fun provideWifiManager(@ApplicationContext ctx: Context): WifiManager {
        return ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    @Provides
    @Singleton
    fun provideConnectivityManager(@ApplicationContext ctx: Context): ConnectivityManager {
        return ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Provides
    @Singleton
    fun provideShellRunner(): ShellRunner = DefaultShellRunner()

    @Provides
    @Singleton
    fun provideSuShellRunner(shell: ShellRunner): SuShellRunner = SuShellRunner(shell)
}
