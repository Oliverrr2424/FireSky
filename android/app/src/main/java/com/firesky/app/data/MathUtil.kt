package com.firesky.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

fun clamp(value: Double, min: Double = 0.0, max: Double = 100.0): Double =
    max(min, min(max, value))

fun scoreRange(value: Double?, ideal: Double, tolerance: Double, max: Double = 100.0): Double {
    if (value == null || value.isNaN()) return 45.0
    return clamp(max - (abs(value - ideal) / tolerance) * max)
}

fun scoreLowerBetter(value: Double?, good: Double, bad: Double): Double {
    if (value == null || value.isNaN()) return 55.0
    return clamp(((bad - value) / (bad - good)) * 100.0)
}

fun scoreHigherBetter(value: Double?, good: Double, great: Double): Double {
    if (value == null || value.isNaN()) return 55.0
    return clamp(((value - good) / (great - good)) * 100.0)
}

fun scoreBand(value: Double?, outerLow: Double, innerLow: Double, innerHigh: Double, outerHigh: Double): Double {
    if (value == null || value.isNaN()) return 45.0
    if (value <= outerLow || value >= outerHigh) return 0.0
    if (value >= innerLow && value <= innerHigh) return 100.0
    if (value < innerLow) return clamp(((value - outerLow) / (innerLow - outerLow)) * 100.0)
    return clamp(((outerHigh - value) / (outerHigh - innerHigh)) * 100.0)
}

fun weightedAverage(pairs: List<Pair<Double?, Double>>): Double {
    val usable = pairs.filter { (value, _) -> value != null && !value.isNaN() }
    if (usable.isEmpty()) return 0.0
    val totalWeight = usable.sumOf { it.second }
    return usable.sumOf { (value, weight) -> value!! * weight } / totalWeight
}

fun weightedProductScore(pairs: List<Pair<Double?, Double>>, floor: Double = 0.03): Double {
    val usable = pairs.filter { (value, _) -> value != null && !value.isNaN() }
    if (usable.isEmpty()) return 0.0
    val totalWeight = usable.sumOf { it.second }
    var product = 1.0
    usable.forEach { (value, weight) ->
        val normalized = max(floor, clamp(value!!) / 100.0)
        product *= normalized.pow(weight / totalWeight)
    }
    return clamp(product * 100.0)
}

fun avg(values: List<Double?>): Double? {
    val usable = values.filter { it != null && !it.isNaN() }
    if (usable.isEmpty()) return null
    return usable.sumOf { it!! } / usable.size
}

fun standardDeviation(values: List<Double?>): Double {
    val usable = values.filter { it != null && !it.isNaN() }
    if (usable.isEmpty()) return 0.0
    val mean = avg(usable) ?: return 0.0
    return kotlin.math.sqrt(avg(usable.map { (it!! - mean) * (it - mean) }) ?: 0.0)
}

fun weightedMean(values: List<Pair<Double?, Double>>): Double? {
    val usable = values.filter { (value, weight) -> value != null && !value.isNaN() && weight > 0 }
    if (usable.isEmpty()) return null
    val totalWeight = usable.sumOf { it.second }
    return usable.sumOf { (value, weight) -> value!! * weight } / totalWeight
}
