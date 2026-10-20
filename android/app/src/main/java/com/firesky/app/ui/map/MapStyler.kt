package com.firesky.app.ui.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

private val LINE_LAYER_HINTS = listOf(
    "boundary",
    "highway_major",
    "highway_motorway",
    "highway_minor",
    "road",
    "waterway",
)

private fun isReferenceLine(id: String): Boolean = LINE_LAYER_HINTS.any { id.contains(it) }

fun enhanceMapReferenceStyle(style: Style) {
    style.layers.forEach { layer ->
        try {
            if (layer is SymbolLayer && layer.textField.value != null) {
                layer.setProperties(
                    PropertyFactory.textColor("#1C344F"),
                    PropertyFactory.textHaloColor("rgba(255, 255, 255, 0.88)"),
                    PropertyFactory.textHaloWidth(1.15f),
                    PropertyFactory.textHaloBlur(0.15f),
                )
            }
            if (layer is LineLayer && layer.id.contains("boundary")) {
                layer.setProperties(
                    PropertyFactory.lineColor("#273F5C"),
                    PropertyFactory.lineOpacity(0.68f),
                    PropertyFactory.lineWidth(1f),
                )
            }
        } catch (_: Exception) {
            // Some imported styles lock paint properties behind expressions.
        }
    }
}

fun addReferenceLineOverlays(style: Style, beforeId: String?, passes: Int = 4) {
    val layers = style.layers.toList()
    val originals = layers.filterIsInstance<LineLayer>().filter { isReferenceLine(it.id) && !it.id.startsWith("fire-sky-reference-") }
    originals.forEach { layer ->
        for (pass in 1..passes) {
            val layerId = if (pass == 1) "fire-sky-reference-${layer.id}" else "fire-sky-reference-${layer.id}-p$pass"
            if (style.getLayer(layerId) != null) continue
            val sourceId = layer.sourceId ?: continue
            val isBoundary = layer.id.contains("boundary")
            try {
                val duplicate = LineLayer(layerId, sourceId)
                layer.sourceLayer?.let { duplicate.setSourceLayer(it) }
                layer.filter?.let { duplicate.setFilter(it) }
                duplicate.minZoom = layer.minZoom
                duplicate.maxZoom = layer.maxZoom
                val widthExpression = if (isBoundary) {
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(4, 0.45),
                        Expression.stop(7, 1.15),
                        Expression.stop(10, 1.8),
                    )
                } else {
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(4, 0.35),
                        Expression.stop(7, 1.05),
                        Expression.stop(10, 2.2),
                    )
                }
                duplicate.setProperties(
                    PropertyFactory.lineColor(if (isBoundary) "#213A55" else "rgba(255, 255, 255, 0.82)"),
                    PropertyFactory.lineOpacity(if (isBoundary) 0.72f else 0.62f),
                    PropertyFactory.lineWidth(widthExpression),
                    PropertyFactory.lineBlur(if (isBoundary) 0f else 0.15f),
                )
                if (beforeId != null) {
                    style.addLayerBelow(duplicate, beforeId)
                } else {
                    style.addLayer(duplicate)
                }
            } catch (_: Exception) {
                // Optional reference overlays should never break the map.
            }
        }
    }
}

fun removeReferenceLineOverlays(style: Style) {
    style.layers
        .filter { it.id.startsWith("fire-sky-reference-") }
        .reversed()
        .forEach { layer ->
            try {
                style.removeLayer(layer.id)
            } catch (_: Exception) {
                // Style may already be tearing down.
            }
        }
}

fun firstSymbolLayerId(style: Style): String? =
    style.layers.firstOrNull { it is SymbolLayer }?.id

@Suppress("unused")
private fun argb(hex: String, alpha: Int = 255): Int = Color.parseColor(hex).let {
    Color.argb(alpha, Color.red(it), Color.green(it), Color.blue(it))
}
