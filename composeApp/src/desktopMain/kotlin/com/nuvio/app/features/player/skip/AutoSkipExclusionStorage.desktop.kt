package com.nuvio.app.features.player.skip

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object AutoSkipExclusionStorage {
    private val store = DesktopStorage.store("auto_skip_exclusions")
    private fun key(profileId: Int): String = ProfileScopedKey.of("auto_skip_exclusions", profileId)

    actual fun load(profileId: Int): String? = store.getString(key(profileId))
    actual fun save(profileId: Int, payload: String) = store.putString(key(profileId), payload)
    actual fun remove(profileId: Int) = store.remove(key(profileId))
}
