package com.example.surveyingapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "coordinates")
data class Coordinate(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long,
    val icon: String,
    val color: Int
)
