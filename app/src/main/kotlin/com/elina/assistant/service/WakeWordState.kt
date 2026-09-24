package com.elina.assistant.service

/**
 * Wake-word feature lifecycle, set by [WakeWordService] and observed by the UI. This tracks
 * the *service's* real state honestly — it is not the same as the persisted user preference
 * (that lives in AppConfig): the service can be DISABLED/ERROR even while the user's last
 * choice was "on", e.g. after a permission was revoked or the platform refused to start it.
 */
enum class WakeWordState {
    /** Feature is off / service is not running. */
    DISABLED,

    /** Service and foreground notification started; about to begin listening. */
    READY,

    /** Actively listening for "Hey Elina". */
    LISTENING_FOR_WAKE,

    /** Wake phrase just matched. */
    WAKE_DETECTED,

    /** Handing off to the main conversation UI. */
    STARTING_CONVERSATION,

    /** Something failed (recognizer unavailable, permission revoked, foreground-service start
     * refused by the platform, etc.) — listening has stopped and will not silently retry forever. */
    ERROR;

    companion object {
        @Volatile
        var current: WakeWordState = DISABLED
            private set

        @Volatile
        private var listener: ((WakeWordState) -> Unit)? = null

        /** Registers the single observer (the Activity) and immediately reports the current state. */
        fun setListener(l: ((WakeWordState) -> Unit)?) {
            listener = l
            l?.invoke(current)
        }

        fun update(state: WakeWordState) {
            current = state
            listener?.invoke(state)
        }
    }
}
