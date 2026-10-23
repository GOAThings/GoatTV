package cc.slokker.itv

import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.i18n.I18n
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

class ItvApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        I18n.s = Strings.of(this)
        container = AppContainer(this)
    }

    // Os logos vêm dos servidores IPTV: usar o mesmo cliente (e User-Agent) das listas.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.iptvHttp })) }
            .build()
}
