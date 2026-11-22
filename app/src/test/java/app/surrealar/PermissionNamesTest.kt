package app.surrealar

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionNamesTest {
    @Test fun `permission names are logged short, without the package prefix`() {
        assertEquals("[ACCESS_FINE_LOCATION,CAMERA]",
            permissionNames(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.CAMERA")))
        assertEquals("[]", permissionNames(emptySet()))
    }
}
