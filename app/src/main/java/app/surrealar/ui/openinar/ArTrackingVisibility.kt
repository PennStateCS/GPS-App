package app.surrealar.ui.openinar

/**
 * When the AR screen may draw its geospatial content. Every Filament model on the AR screen is
 * geospatial: coordinate models, the debug test grid and the test cube are all Earth anchors.
 *
 * - Camera tracking lost: the view matrix is stale, so models would be frozen on screen.
 * - Earth tracking lost (camera still tracking): anchor poses are no longer refreshed against the
 *   globe and labels/pins vanish, so models left in the scene would sit at stale poses with no label.
 *
 * In both cases models are hidden (the renderer draws its empty scene) while their assets stay cached
 * and in the model scene, so they reappear on the next tracking frame without a reload.
 */
internal object ArTrackingVisibility {
    fun drawGeospatialModels(cameraTracking: Boolean, earthTracking: Boolean): Boolean =
        cameraTracking && earthTracking
}
