package com.kiyoscripts.nexusclone.core.instance

/**
 * Lifecycle state of a single clone instance.
 *
 * Transitions are owned exclusively by [InstanceManager]; nothing else writes this value.
 *
 *   CREATED    -> INSTALLED   (apk copied into the virtual root)
 *   INSTALLED  -> COLD        (first launch intent issued)
 *   COLD       -> WARM        (guest process attached)
 *   WARM       -> FOREGROUND  (guest activity resumed)
 *   FOREGROUND -> BACKGROUND  (guest activity paused)
 *   BACKGROUND -> WARM        (guest activity resumed again)
 *   WARM       -> FROZEN      (explicit freeze, process killed, data kept)
 *   FROZEN     -> COLD        (unfreeze, next launch is a cold start)
 *   any        -> DEAD        (uninstalled or data wiped)
 */
enum class InstanceState {
    CREATED,
    INSTALLED,
    COLD,
    WARM,
    FOREGROUND,
    BACKGROUND,
    FROZEN,
    DEAD;

    /** True when the guest process is expected to be resident. */
    val isResident: Boolean
        get() = this == WARM || this == FOREGROUND || this == BACKGROUND

    /** True when the instance can be launched without a reinstall. */
    val isLaunchable: Boolean
        get() = this == INSTALLED || this == COLD || this == WARM ||
                this == FOREGROUND || this == BACKGROUND || this == FROZEN

    companion object {
        fun fromName(name: String?): InstanceState =
            entries.firstOrNull { it.name == name } ?: CREATED
    }
}
