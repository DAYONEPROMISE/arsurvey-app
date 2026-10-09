package com.example.arsurvey.capture

import android.content.Context
import android.util.Log
import com.example.arsurvey.capture.data.CaptureDatabase
import com.example.arsurvey.capture.data.toEntity
import com.example.arsurvey.capture.data.toModel
import com.example.arsurvey.capture.model.CapturedObject
import com.example.arsurvey.capture.model.CapturedPhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persistence for the capture-first workflow.
 *
 * Split of responsibilities (per the plan): the heavy binaries stay as **files** on app-internal
 * storage, while the catalog data lives in **Room**.
 *
 * ```
 * filesDir/captures/<objectId>/
 *   ├── photo_0.jpg   photo_1.jpg   photo_2.jpg     (full-resolution camera images)
 *   └── depth_0.raw   depth_1.raw   depth_2.raw     (raw uint16 mm depth, when available)
 *
 * Room (capture.db): captured_objects  +  captured_photos (paths + ARCore metadata)
 * ```
 *
 * The catalog observes [observeAllObjects]; capture writes files with [writePhoto] then records rows
 * with [addObject] / [addPhoto]. There is no JSON manifest anymore — Room is the index.
 */
class CaptureRepository(context: Context) {

    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, "captures").apply { mkdirs() }
    private val dao = CaptureDatabase.getInstance(appContext).captureDao()

    /** Directory holding one object's files; created on demand. */
    private fun objectDir(objectId: String): File = File(root, objectId).apply { mkdirs() }

    /**
     * Writes one photo's binaries (JPEG + optional raw depth) to disk and returns the [CapturedPhoto]
     * record, with [com.example.arsurvey.capture.model.ARCoreFrameMetadata.depthRawPath] filled in
     * when depth was persisted. Does NOT touch the database — call [addPhoto] with the result.
     */
    fun writePhoto(
        objectId: String,
        index: Int,
        jpeg: ByteArray,
        capture: CaptureFrameExtractor.RawFrameCapture,
    ): CapturedPhoto {
        val dir = objectDir(objectId)
        val jpegFile = File(dir, "photo_$index.jpg")
        jpegFile.writeBytes(jpeg)

        var depthRelPath: String? = null
        val raw = capture.rawDepthMm
        if (raw != null) {
            val depthFile = File(dir, "depth_$index.raw")
            depthFile.writeBytes(shortsToLittleEndianBytes(raw))
            depthRelPath = depthFile.name
        }

        val metadata = capture.metadata.copy(depthRawPath = depthRelPath)
        Log.i(
            TAG,
            "Persisted photo #$index → ${jpegFile.absolutePath} (${jpeg.size} B)" +
                (depthRelPath?.let { ", depth → $it (${raw!!.size} samples)" } ?: ", no depth"),
        )
        return CapturedPhoto(index = index, imagePath = jpegFile.absolutePath, metadata = metadata)
    }

    /** Inserts the object row (call once, when its first photo is persisted). */
    suspend fun addObject(obj: CapturedObject) = dao.insertObject(obj.toEntity())

    /** Inserts one photo row under [objectId]. */
    suspend fun addPhoto(objectId: String, photo: CapturedPhoto) =
        dao.insertPhoto(photo.toEntity(objectId))

    /** All captured objects (newest first), as domain models — the catalog observes this. */
    fun observeAllObjects(): Flow<List<CapturedObject>> =
        dao.observeAllObjects().map { list -> list.map { it.toModel() } }

    /** One object by id, or null if not found. */
    suspend fun getObject(objectId: String): CapturedObject? = dao.getObject(objectId)?.toModel()

    /** Delete one photo's row + files (for per-photo retake). */
    suspend fun deletePhoto(objectId: String, index: Int) {
        dao.deletePhoto(objectId, index)
        try { File(objectDir(objectId), "photo_$index.jpg").delete() } catch (_: Throwable) {}
        try { File(objectDir(objectId), "depth_$index.raw").delete() } catch (_: Throwable) {}
    }

    /** Delete an object, its rows and its files (catalog delete action). */
    suspend fun deleteObject(objectId: String) {
        dao.deletePhotosOf(objectId)
        dao.deleteObject(objectId)
        try { objectDir(objectId).deleteRecursively() } catch (_: Throwable) {}
    }

    /** Export one object's photo metadata as CSV; returns the file. */
    suspend fun exportCsv(objectId: String): File? {
        val obj = dao.getObject(objectId)?.toModel() ?: return null
        val out = File(objectDir(objectId), "metadata.csv")
        val sb = StringBuilder("photo_index,captured_at,tracking,depth_available,depth_wxh,pose_tx,pose_ty,pose_tz\n")
        for (p in obj.photos.sortedBy { it.index }) {
            val m = p.metadata
            val t = m.poseTranslation
            sb.append("${p.index},${m.capturedAtEpochMs},${m.trackingState ?: "unknown"},${m.depthAvailable},")
            sb.append("${m.depthWidth ?: 0}x${m.depthHeight ?: 0},")
            sb.append(if (t != null && t.size >= 3) "${t[0]},${t[1]},${t[2]}" else ",,")
            sb.append("\n")
        }
        out.writeText(sb.toString())
        return out
    }

    private fun shortsToLittleEndianBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }

    companion object {
        private const val TAG = "CaptureRepository"
    }
}
