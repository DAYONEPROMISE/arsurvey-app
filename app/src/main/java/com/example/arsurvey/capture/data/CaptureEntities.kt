package com.example.arsurvey.capture.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Room schema for the capture catalog.
 *
 * Design choice (per the plan): the heavy binaries — the JPEG and the raw depth dump — stay as files
 * on disk; Room stores their **paths** plus the ARCore metadata. A handful of frequently-useful
 * fields get their own columns so they're queryable for analysis; the complete [ARCoreFrameMetadata]
 * is kept losslessly in [CapturedPhotoEntity.metadataJson] (serialized by [CaptureMetadataJson]).
 */
@Entity(tableName = "captured_objects")
data class CapturedObjectEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "captured_photos",
    foreignKeys = [
        ForeignKey(
            entity = CapturedObjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("objectId")],
)
data class CapturedPhotoEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val objectId: String,
    val photoIndex: Int,
    /** Absolute path to the full-resolution JPEG on app-internal storage. */
    val imagePath: String,
    /** Absolute path to the raw depth dump, or null when no depth was persisted. */
    val depthRawPath: String?,
    // ---- Promoted queryable columns (also present inside metadataJson) ----
    val capturedAtEpochMs: Long,
    val frameTimestampNs: Long?,
    val trackingState: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val depthAvailable: Boolean,
    val depthWidth: Int?,
    val depthHeight: Int?,
    /** Full [ARCoreFrameMetadata] serialized to JSON for lossless retrieval. */
    val metadataJson: String,
)

/**
 * One object with all its photos, ordered by [CapturedPhotoEntity.photoIndex]. Room populates
 * [photos] via the [Relation] on `objectId`.
 */
data class ObjectWithPhotos(
    @androidx.room.Embedded val obj: CapturedObjectEntity,
    @Relation(parentColumn = "id", entityColumn = "objectId")
    val photos: List<CapturedPhotoEntity>,
)
