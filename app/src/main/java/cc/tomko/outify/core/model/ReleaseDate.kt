package cc.tomko.outify.core.model

import kotlinx.serialization.Serializable

enum class ReleaseDatePrecision {
    Year,
    Month,
    Day
}

@Serializable
data class ReleaseDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {
    fun isSet(): Boolean =
        year != null
}

fun ReleaseDate.precision(): ReleaseDatePrecision =
    when {
        day != null -> ReleaseDatePrecision.Day
        month != null -> ReleaseDatePrecision.Month
        else -> ReleaseDatePrecision.Year
    }