package com.myrunningapp.domain.model

/** How long the pre-run countdown lasts. Chosen in Preferences. */
enum class CountdownLength(val seconds: Int) {
    OFF(0),
    TEN(10),
    THIRTY(30);

    companion object {
        val DEFAULT = THIRTY

        fun fromSeconds(seconds: Int): CountdownLength =
            entries.firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}
