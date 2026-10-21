package com.firesky.app.data

val DEFAULT_PLACE = Place(
    name = "San Francisco",
    admin1 = "California",
    countryCode = "US",
    latitude = 37.7749,
    longitude = -122.4194,
)

val PRESET_PLACES = listOf(
    Place("San Francisco", "California", "US", 37.7749, -122.4194),
    Place("New York", "New York", "US", 40.7128, -74.0060),
    Place("Vancouver", "British Columbia", "CA", 49.2827, -123.1207),
    Place("Mexico City", "CDMX", "MX", 19.4326, -99.1332),
    Place("Toronto", "Ontario", "CA", 43.6532, -79.3832),
)

const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
const val SUNSET_ALERT_THRESHOLD = 70
