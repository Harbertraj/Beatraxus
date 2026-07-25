package com.beatraxus.app.model

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? {
        return value?.joinToString(",")
    }

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? {
        return value?.split(",")?.filter { it.isNotBlank() }?.map { it.toFloat() }?.toFloatArray()
    }
}
