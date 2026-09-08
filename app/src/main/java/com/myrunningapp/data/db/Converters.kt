package com.myrunningapp.data.db

import androidx.room.TypeConverter
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Sex
import java.time.Instant

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
}
