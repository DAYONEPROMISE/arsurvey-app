package com.example.arsurvey.cloud

import android.opengl.Matrix
import com.example.arsurvey.depth.Depth16

/**
 * Milestone 5: turns the masked depth image into a **world-space** 3D point cloud.
 *
 * For each depth pixel (u,v) that (a) has a valid depth and (b) falls inside the target
 * mask, we invert the pinhole camera model to get its 3D position in the **camera** frame,
 * then transform by the camera pose to get **world** coordinates (so the cloud stays put as
 * the phone moves):
 *
 *   Z = depth_mm / 1000                      // millimeters → meters
 *   Xc = (u − cx) · Z / fx                   // right
 *   Yc = −(v − cy) · Z / fy                  // up   (image v grows downward → negate)
 *   Zc = −Z                                  // ARCore camera looks down −Z → in-front is −Z
 *   world = pose · (Xc, Yc, Zc, 1)           // pose = world_T_camera
 *
 * Critical: the depth image is lower-res than the RGB frame and has its **own** intrinsics
 * (`fx,fy,cx,cy` here are already scaled to the depth resolution) — never use RGB intrinsics
 * on the depth image. The mask (in RGB/camera-image pixels) is sampled at each depth pixel's
 * corresponding location.
 */
object PointCloudBuilder {

    /**
     * @param depth      DEPTH16 samples, row-major [dw]*[dh]; low 13 bits = mm (0 = invalid),
     *                   top 3 bits = confidence. Decoded here via [Depth16.DEPTH_MASK].
     * @param fx fy cx cy intrinsics **at depth resolution**.
     * @param pose       column-major 4×4 world_T_camera (from `Camera.getPose().toMatrix`).
     * @param maskBinary target mask in camera-image pixel space, row-major [maskW]*[maskH].
     * @return packed world points [x0,y0,z0, x1,y1,z1, …]; empty if nothing qualifies.
     */
    fun build(
        depth: ShortArray, dw: Int, dh: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        pose: FloatArray,
        maskBinary: BooleanArray, maskW: Int, maskH: Int,
    ): FloatArray {
        if (dw == 0 || dh == 0 || maskW == 0 || maskH == 0) return FloatArray(0)
        // Depth-pixel → mask/image-pixel scale (they cover the same view at different res).
        val sx = maskW.toFloat() / dw
        val sy = maskH.toFloat() / dh

        val packed = FloatArray(dw * dh * 3)
        var n = 0
        val cam = FloatArray(4)
        val world = FloatArray(4)

        for (v in 0 until dh) {
            val iy = (v * sy).toInt()
            if (iy < 0 || iy >= maskH) continue
            val maskRow = iy * maskW
            val depthRow = v * dw
            for (u in 0 until dw) {
                val mm = depth[depthRow + u].toInt() and Depth16.DEPTH_MASK // DEPTH16: low 13 bits
                if (mm == 0) continue                       // invalid depth
                val ix = (u * sx).toInt()
                if (ix < 0 || ix >= maskW) continue
                if (!maskBinary[maskRow + ix]) continue     // outside the object

                val z = mm / 1000f
                cam[0] = (u - cx) * z / fx
                cam[1] = -(v - cy) * z / fy
                cam[2] = -z
                cam[3] = 1f
                Matrix.multiplyMV(world, 0, pose, 0, cam, 0)

                packed[n++] = world[0]
                packed[n++] = world[1]
                packed[n++] = world[2]
            }
        }
        return if (n == packed.size) packed else packed.copyOf(n)
    }
}
