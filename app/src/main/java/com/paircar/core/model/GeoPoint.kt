package com.paircar.core.model

/** WGS84 좌표. NaviHelper v3.0.5부터 좌표 순서가 (latitude, longitude)로 통일되었다. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)
