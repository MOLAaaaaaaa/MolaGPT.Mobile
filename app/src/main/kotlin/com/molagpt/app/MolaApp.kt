package com.molagpt.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.molagpt.app.di.AppContainer

/** Application：进程内创建唯一 [AppContainer]。 */
class MolaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            context = this,
            versionName = BuildConfig.VERSION_NAME,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            isDebug = BuildConfig.DEBUG,
        )
    }
}

/** 经 CompositionLocal 下发容器，供各屏的 ViewModel 工厂取依赖。 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供")
}
