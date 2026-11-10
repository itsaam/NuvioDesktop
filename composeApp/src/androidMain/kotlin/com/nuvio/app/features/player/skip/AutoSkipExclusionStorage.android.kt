package com.nuvio.app.features.player.skip

internal actual object AutoSkipExclusionStorage {
    actual fun load(profileId: Int): String? = null
    actual fun save(profileId: Int, payload: String): Unit = error("Auto-skip exclusions are desktop-only")
    actual fun remove(profileId: Int) = Unit
}
