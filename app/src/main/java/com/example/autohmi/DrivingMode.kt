package com.example.autohmi

enum class DrivingMode(
    val consumptionMultiplier: Double
) {
    ECO(0.85),
    NORMAL(1.0),
    SPORT(1.25)
}
