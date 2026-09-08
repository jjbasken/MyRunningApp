package com.myrunningapp.domain.model

/**
 * The user's body data, stored canonically in metric. The UI converts to/from
 * pounds and inches at its edges (see [com.myrunningapp.domain.Units]).
 *
 * A snapshot of [weightKg] is copied onto every [Run] when it finishes, so
 * editing this later never rewrites past calorie numbers.
 */
data class Profile(
    val weightKg: Double,
    val heightCm: Double,
    val age: Int,
    val sex: Sex,
) {
    companion object {
        /** Sensible starting point shown until the user saves their own. */
        val DEFAULT = Profile(
            weightKg = 70.0,
            heightCm = 175.0,
            age = 35,
            sex = Sex.MALE,
        )
    }
}
