package tech.mmarca.openwatch.link

import android.content.Context
import tech.mmarca.openwatch.R

/**
 * The first-party OpenLink clients OpenWatch knows: their package names and the signers it
 * trusts them under without asking (`res/values/openlink.xml`). The name and the key count
 * together; any other app asks on the Connected apps screen.
 */
class FirstPartyApps(private val packageNames: Set<String>, private val knownSigners: Set<String>) {

    /** Each first-party package with the signers it may come under, for the OpenLink host. */
    val knownClients: Map<String, Set<String>> get() = packageNames.associateWith { knownSigners }

    /**
     * True when an app uses a first-party name but did not come under a first-party key
     * ([knownSigner], as the host recorded it at its last hello): an F-Droid or self-built
     * copy, or another app that took the name.
     */
    fun usesNameWithoutKey(packageName: String, knownSigner: Boolean): Boolean =
        packageName in packageNames && !knownSigner

    companion object {
        fun from(context: Context): FirstPartyApps = FirstPartyApps(
            packageNames = context.resources.getStringArray(R.array.openlink_first_party_packages).toSet(),
            knownSigners = context.resources.getStringArray(R.array.openlink_known_signers).toSet(),
        )
    }
}
