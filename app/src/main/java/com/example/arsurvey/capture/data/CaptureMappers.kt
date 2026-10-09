package com.example.arsurvey.capture.data

import com.example.arsurvey.capture.model.ARCoreFrameMetadata
import com.example.arsurvey.capture.model.CapturedObject
import com.example.arsurvey.capture.model.CapturedPhoto

/**
 * Mapping between the Room entities and the domain models the UI renders. Metadata is carried
 * losslessly through the JSON column via [CaptureMetadataJson].
 */

/** Build the photo row for insertion, promoting the queryable columns from [metadata]. */
fun CapturedPhoto.toEntity(objectId: String): CapturedPhotoEntity = CapturedPhotoEntity(
    objectId = objectId,
    photoIndex = index,
    imagePath = imagePath,
    depthRawPath = metadata.depthRawPath,
    capturedAtEpochMs = metadata.capturedAtEpochMs,
    frameTimestampNs = metadata.frameTimestampNs,
    trackingState = metadata.trackingState,
    imageWidth = metadata.imageWidth,
    imageHeight = metadata.imageHeight,
    depthAvailable = metadata.depthAvailable,
    depthWidth = metadata.depthWidth,
    depthHeight = metadata.depthHeight,
    metadataJson = CaptureMetadataJson.toJson(metadata),
)

fun CapturedObject.toEntity(): CapturedObjectEntity =
    CapturedObjectEntity(id = id, createdAtEpochMs = createdAtEpochMs)

fun CapturedPhotoEntity.toModel(): CapturedPhoto = CapturedPhoto(
    index = photoIndex,
    imagePath = imagePath,
    metadata = metadataJson.let(CaptureMetadataJson::fromJson),
)

fun ObjectWithPhotos.toModel(): CapturedObject = CapturedObject(
    id = obj.id,
    createdAtEpochMs = obj.createdAtEpochMs,
    photos = photos.sortedBy { it.photoIndex }.map { it.toModel() },
)
