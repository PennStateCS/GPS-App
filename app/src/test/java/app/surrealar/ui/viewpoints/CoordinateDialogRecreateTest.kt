package app.surrealar.ui.viewpoints

import androidx.fragment.app.FragmentFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android rebuilds dialogs with the no-argument constructor; both dialogs must have one. */
@RunWith(RobolectricTestRunner::class)
class CoordinateDialogRecreateTest {
    private fun instantiate(name: String) = FragmentFactory().instantiate(javaClass.classLoader!!, name)

    @Test fun `both coordinate dialogs can be re-instantiated by the framework`() {
        assertTrue(instantiate(AddCoordinateDialogFragment::class.java.name) is AddCoordinateDialogFragment)
        assertTrue(instantiate(EditCoordinateDialogFragment::class.java.name) is EditCoordinateDialogFragment)
    }
}