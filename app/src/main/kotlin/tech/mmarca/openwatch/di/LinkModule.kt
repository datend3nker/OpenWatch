package tech.mmarca.openwatch.di

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import tech.mmarca.openlink.host.OpenLinkCallerResolver
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openlink.host.OpenLinkTrustStore
import tech.mmarca.openlink.host.SharedPreferencesTrustStorage
import tech.mmarca.openwatch.MainActivity
import tech.mmarca.openwatch.link.FirstPartyApps
import tech.mmarca.openwatch.link.OpenWatchLinkCapabilities

/** The OpenLink host: the trust rules, the binder, and what OpenWatch offers through it. */
@Module
@InstallIn(SingletonComponent::class)
object LinkModule {

    @Provides
    @Singleton
    fun provideOpenLinkHostCore(@ApplicationContext context: Context): OpenLinkHostCore = OpenLinkHostCore(
        store = OpenLinkTrustStore(SharedPreferencesTrustStorage.create(context)),
        knownClients = FirstPartyApps.from(context).knownClients,
    )

    @Provides
    @Singleton
    fun provideOpenLinkHost(
        @ApplicationContext context: Context,
        core: OpenLinkHostCore,
        capabilities: OpenWatchLinkCapabilities,
    ): OpenLinkHost = OpenLinkHost(
        core = core,
        resolver = OpenLinkCallerResolver(context),
        capabilities = capabilities,
        consentIntent = { packageName ->
            PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPENLINK_CONSENT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        },
    )
}
