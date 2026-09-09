package mihon.desktop.loader

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import mihon.desktop.loader.prefs.AppPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/**
 * Registers the small set of Injekt singletons that Mihon's `:source-api`/`:platform-compat`
 * classes expect to find on the classpath at extension-load time (mirrors what Mihon's real
 * Application class wires up on Android, minus everything extensions don't actually need).
 *
 * Call [bootstrap] once before the first [ExtensionLoader.load].
 */
object DesktopExtensionRuntime {
    private var bootstrapped = false

    @Synchronized
    fun bootstrap() {
        if (bootstrapped) return

        val app = Application()
        Injekt.addSingleton<Context>(app)
        Injekt.addSingleton<Application>(app)
        // DoH settings are read once here: NetworkHelper (and the OkHttpClient
        // sources hold on to) is built at this point, so changing the setting
        // requires an app restart. See DohDns.
        Injekt.addSingleton(
            NetworkHelper(
                app,
                dohEnabled = AppPreferences.getBoolean(
                    AppPreferences.KEY_DOH_ENABLED,
                    AppPreferences.DEFAULT_DOH_ENABLED,
                ),
                dohProvider = AppPreferences.getString(
                    AppPreferences.KEY_DOH_PROVIDER,
                    AppPreferences.DEFAULT_DOH_PROVIDER,
                ),
            ),
        )
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })

        bootstrapped = true
    }
}
