package app.surrealar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailBitmapsTest {
    @Test fun `sample size keeps the image at least the requested size`() {
        assertEquals(1, ThumbnailBitmaps.sampleSize(300, 300, 256))
        assertEquals(4, ThumbnailBitmaps.sampleSize(1920, 1080, 256))
        assertEquals(16, ThumbnailBitmaps.sampleSize(4096, 4096, 256))
    }
}