package com.kiyoscripts.nexusclone.core.instance

/**
 * Lifecycle state of a single clone instance.
 *
 * Transitions are owned exclusively by [InstanceManager]; nothing else writes this value.
 *
 * The table below is what the code actually does, not what it was meant to do:
 *
 *   (insert)   -> CREATED     (row written before the install is confirmed)
 *   CREATED    -> INSTALLED   (row persisted, apk present in the virtual root)
 *   INSTALLED  -> COLD        (launch() issues the launch intent)
 *   COLD       -> FOREGROUND  (onGuestResumed promotes the row)
 *   FOREGROUND -> BACKGROUND  (onGuestPaused demotes the row)
 *   BACKGROUND -> FOREGROUND  (onGuestResumed promotes it again)
 *   any        -> FROZEN      (explicit freeze, process killed, data kept)
 *   FROZEN     -> INSTALLED   (unfreeze of a never-launched clone)
 *   FROZEN     -> COLD        (unfreeze of a clone that has launched before)
 *   any        -> DEAD        (persisted before the row is deleted, so a crash
 *                              between uninstall and delete leaves a recoverable row)
 *
 * WARM is still a legal value and still counts as resident, but no transition in
 * [InstanceManager] targets it any more: the guest lifecycle callback is the only
 * source of truth for a running process, and it reports FOREGROUND/BACKGROUND.
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
