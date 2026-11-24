package com.myrunningapp.data.db

import androidx.room.TypeConverter
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Sex
import com.myrunningapp.domain.plan.WorkoutStatus
import com.myrunningapp.domain.plan.WorkoutType
import java.time.Instant
import java.time.LocalDate

/** Maps the non-primitive column types to something SQLite can store. */
class Converters {

    @TypeConverter
    fun instantToEpochMilli(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun epochMilliToInstant(epochMilli: Long?): Instant? =
        epochMilli?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun activityTypeToName(type: ActivityType?): String? = type?.name

    @TypeConverter
    fun nameToActivityType(name: String?): ActivityType? =
        name?.let { ActivityType.valueOf(it) }

    @TypeConverter
    fun sexToName(sex: Sex?): String? = sex?.name

    @TypeConverter
    fun nameToSex(name: String?): Sex? = name?.let { Sex.valueOf(it) }

    @TypeConverter
    fun healthSyncStateToName(state: HealthSyncState?): String? = state?.name

    @TypeConverter
    fun nameToHealthSyncState(name: String?): HealthSyncState? =
        name?.let { HealthSyncState.valueOf(it) }

    /** A calendar day with no time zone, stored as days since 1970-01-01. */
    @TypeConverter
    fun localDateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun workoutTypeToName(type: WorkoutType?): String? = type?.name

    /** Unknown names fall back rather than crash, in case a type is ever retired. */
    @TypeConverter
    fun nameToWorkoutType(name: String?): WorkoutType? =
        name?.let { n -> WorkoutType.entries.firstOrNull { it.name == n } ?: WorkoutType.OTHER }

    @TypeConverter
    fun workoutStatusToName(status: WorkoutStatus?): String? = status?.name

    @TypeConverter
    fun nameToWorkoutStatus(name: String?): WorkoutStatus? =
        name?.let { n -> WorkoutStatus.entries.firstOrNull { it.name == n } ?: WorkoutStatus.PLANNED }
}
