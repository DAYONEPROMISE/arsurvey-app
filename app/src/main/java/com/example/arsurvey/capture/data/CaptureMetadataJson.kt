package com.example.arsurvey.capture.data

import com.example.arsurvey.capture.model.ARCoreFrameMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for (de)serializing [ARCoreFrameMetadata] to/from a JSON string.
 *
 * The Room `CapturedPhotoEntity.metadataJson` column stores the full metadata losslessly via this
 * object, so every ARCore field survives a round-trip even though only a few are promoted to their
 * own queryable columns. Unavailable values remain `null` (never faked). Uses `org.json` (bundled
 * with Android) so no serialization dependency is required.
 */
object CaptureMetadataJson {

    fun toJson(m: ARCoreFrameMetadata): String = JSONObject().apply {
        putOrNull("frameTimestampNs", m.frameTimestampNs)
        put("capturedAtEpochMs", m.capturedAtEpochMs)
        putOrNull("trackingState", m.trackingState)
        putFloatArray("poseTranslation", m.poseTranslation)
        putFloatArray("poseRotationQuaternion", m.poseRotationQuaternion)
        putFloatArray("cameraToWorldMatrix", m.cameraToWorldMatrix)
        putFloatArray("worldToCameraMatrix", m.worldToCameraMatrix)
        putFloatArray("projectionMatrix", m.projectionMatrix)
        put("projectionNear", m.projectionNear.toDouble())
        put("projectionFar", m.projectionFar.toDouble())
        putFloatArray("intrinsicsFocalLength", m.intrinsicsFocalLength)
        putFloatArray("intrinsicsPrincipalPoint", m.intrinsicsPrincipalPoint)
        putIntArray("intrinsicsImageDimensions", m.intrinsicsImageDimensions)
        putOrNull("imageWidth", m.imageWidth)
        putOrNull("imageHeight", m.imageHeight)
        put("displayRotationDegrees", m.displayRotationDegrees)
        put("depthSupported", m.depthSupported)
        put("depthAvailable", m.depthAvailable)
        putOrNull("depthWidth", m.depthWidth)
        putOrNull("depthHeight", m.depthHeight)
        putOrNull("depthRawPath", m.depthRawPath)
    }.toString()

    fun fromJson(json: String): ARCoreFrameMetadata = with(JSONObject(json)) {
        ARCoreFrameMetadata(
            frameTimestampNs = longOrNull("frameTimestampNs"),
            capturedAtEpochMs = getLong("capturedAtEpochMs"),
            trackingState = stringOrNull("trackingState"),
            poseTranslation = floatArrayOrNull("poseTranslation"),
            poseRotationQuaternion = floatArrayOrNull("poseRotationQuaternion"),
            cameraToWorldMatrix = floatArrayOrNull("cameraToWorldMatrix"),
            worldToCameraMatrix = floatArrayOrNull("worldToCameraMatrix"),
            projectionMatrix = floatArrayOrNull("projectionMatrix"),
            projectionNear = getDouble("projectionNear").toFloat(),
            projectionFar = getDouble("projectionFar").toFloat(),
            intrinsicsFocalLength = floatArrayOrNull("intrinsicsFocalLength"),
            intrinsicsPrincipalPoint = floatArrayOrNull("intrinsicsPrincipalPoint"),
            intrinsicsImageDimensions = intArrayOrNull("intrinsicsImageDimensions"),
            imageWidth = intOrNull("imageWidth"),
            imageHeight = intOrNull("imageHeight"),
            displayRotationDegrees = getInt("displayRotationDegrees"),
            depthSupported = getBoolean("depthSupported"),
            depthAvailable = getBoolean("depthAvailable"),
            depthWidth = intOrNull("depthWidth"),
            depthHeight = intOrNull("depthHeight"),
            depthRawPath = stringOrNull("depthRawPath"),
        )
    }
}

// ---- Small org.json helpers (null-safe) --------------------------------------------------

private fun JSONObject.putOrNull(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.putFloatArray(key: String, arr: FloatArray?) {
    if (arr == null) put(key, JSONObject.NULL)
    else put(key, JSONArray().also { a -> arr.forEach { a.put(it.toDouble()) } })
}

private fun JSONObject.putIntArray(key: String, arr: IntArray?) {
    if (arr == null) put(key, JSONObject.NULL)
    else put(key, JSONArray().also { a -> arr.forEach { a.put(it) } })
}

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONObject.floatArrayOrNull(key: String): FloatArray? {
    if (isNull(key)) return null
    val a = getJSONArray(key)
    return FloatArray(a.length()) { a.getDouble(it).toFloat() }
}

private fun JSONObject.intArrayOrNull(key: String): IntArray? {
    if (isNull(key)) return null
    val a = getJSONArray(key)
    return IntArray(a.length()) { a.getInt(it) }
}
