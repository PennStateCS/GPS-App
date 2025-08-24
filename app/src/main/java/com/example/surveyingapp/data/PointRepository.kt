package com.example.surveyingapp.data

import androidx.lifecycle.LiveData

class PointRepository(private val pointDao: PointDao) {
    val allPoints: LiveData<List<Point>> = pointDao.getAllPoints()

    suspend fun insert(point: Point) {
        pointDao.insert(point)
    }

    suspend fun deleteById(id: String) {
        pointDao.deleteById(id)
    }

    suspend fun deleteAll() {
        pointDao.deleteAll()
    }

    suspend fun insertAll(points: List<Point>) {
        points.forEach { pointDao.insert(it) }
    }

    suspend fun update(point: Point) {
        pointDao.update(point)
    }
}
