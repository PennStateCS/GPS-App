package app.surrealar.ui.settings.categories

import android.app.Application
import android.content.DialogInterface
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityOptionsCompat
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.data.backup.BackupImportPlan
import app.surrealar.data.backup.ImportMode
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.usecase.ExportCoordinateBackupUseCase
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase.PartialReplaceDecision
import app.surrealar.settings.model.CoordinateSystemSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Data category lifecycle: exports and imports run on the save scope past the category / fragment
 * view, their UI goes only to the current view, progress (and Cancel) come back on return, and the
 * partial-replace confirmation is asked while the fragment is around and answers CANCEL once it is
 * destroyed. Runs on the paused Robolectric main looper; file work is real (temp files), waited for by
 * condition, never by a fixed sleep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataSettingsLifecycleTest {

    @get:Rule val tmp = TemporaryFolder()

    private class NoopLauncher<I> : ActivityResultLauncher<I>() {
        override fun launch(input: I, options: ActivityOptionsCompat?) {}
        override fun unregister() {}
        override fun getContract(): ActivityResultContract<I, *> = throw UnsupportedOperationException()
    }

    private var existingPoints: List<Coordinate> = emptyList()
    private val repository: CoordinateRepository = Mockito.mock(CoordinateRepository::class.java) { inv ->
        when (inv.method.name) {
            "getAllCoordinatesList" -> existingPoints
            else -> null
        }
    }
    private val settings: CoordinateDisplaySettingsRepository = Mockito.mock(CoordinateDisplaySettingsRepository::class.java).also {
        Mockito.`when`(it.coordinateSystemSettings).thenReturn(flowOf(CoordinateSystemSettings()))
    }

    /** The import seam: records the call, then runs [importBody]. */
    private var importCalls = 0
    private var importBody: suspend (Boolean, suspend (BackupImportPlan, Int) -> PartialReplaceDecision) -> BackupImportPlan =
        { _, _ -> plan(ImportMode.MERGE) }

    private fun plan(mode: ImportMode, skipped: Int = 0, cancelled: Boolean = false) = BackupImportPlan(
        mode, toInsert = List(2) { Mockito.mock(Coordinate::class.java) }, skippedInvalid = List(skipped) { "bad $it" },
        missingModelRefs = emptyList(), duplicateIds = emptyList(), cancelled = cancelled)

    private val host = ControllerHost()

    private fun controller(on: ControllerHost = host) = DataSettingsController(on.fragment, repository, settings,
        Mockito.mock(CoordinateSystemService::class.java), Mockito.mock(ExportCoordinateBackupUseCase::class.java),
        Mockito.mock(ImportCoordinateBackupUseCase::class.java), mainScope(),
        NoopLauncher(), NoopLauncher(), NoopLauncher(),
        runImport = { _, replace, confirm -> importCalls++; importBody(replace, confirm) })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pumpUntil(timeoutMs: Long = 10_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (done()) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun show(c: DataSettingsController): View = host.show(c, mainScope())
    private fun leave(c: DataSettingsController, v: View) {
        host.fragment.container.removeView(v)
        c.onDestroyView()
    }

    private fun backupFile(): Uri = Uri.fromFile(tmp.newFile("backup.json").apply { writeText("[]") })
    private fun progressCard(v: View): View = v.findViewById(R.id.import_progress_container)
    private fun progressText(v: View) = v.findViewById<TextView>(R.id.text_import_progress).text.toString()

    @Test fun `a CSV export result after leaving Data still writes the file`() {
        val c = controller()
        val v = show(c)
        leave(c, v)
        val out = File(tmp.root, "points.csv")

        c.onExportPointsCsvResult(Uri.fromFile(out))

        assertTrue("CSV written with no Data view", pumpUntil { out.exists() && out.length() > 0 })
        assertEquals(View.GONE, progressCard(v).visibility)
    }

    @Test fun `a result delivered to a recreated fragment's controller is handled without a built view`() {
        // After recreation the Activity Result registry delivers to the NEW fragment's launcher, i.e. a
        // new controller that may not have built the Data view yet.
        val fresh = controller(ControllerHost())
        val out = File(tmp.root, "points.csv")

        fresh.onExportPointsCsvResult(Uri.fromFile(out))

        assertTrue(pumpUntil { out.exists() && out.length() > 0 })
    }

    @Test fun `an import keeps running across a category switch, shows its progress again and can be cancelled`() {
        val hold = CompletableDeferred<BackupImportPlan>()
        var cancelled = false
        importBody = { _, _ -> try { hold.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e } }
        val c = controller()
        val first = show(c)

        c.onImportResult(backupFile())
        assertTrue("import reached the use case", pumpUntil { importCalls == 1 })
        // Before the fix the card was looked up as a LinearLayout (it is a MaterialCardView): never shown.
        assertEquals(View.VISIBLE, progressCard(first).visibility)
        assertEquals("Writing…", progressText(first))

        leave(c, first)
        val again = show(c)
        assertEquals("progress shown again on return", View.VISIBLE, progressCard(again).visibility)
        assertEquals("Writing…", progressText(again))
        assertEquals(View.VISIBLE, progressCard(first).visibility)   // the old view is no longer updated

        again.findViewById<Button>(R.id.btn_cancel_import).performClick()
        assertTrue("cancelled", pumpUntil { cancelled && progressCard(again).visibility == View.GONE })
        assertEquals(1, importCalls)
    }

    @Test fun `an import that finishes while Data is hidden shows no stale progress on return`() {
        val hold = CompletableDeferred<BackupImportPlan>()
        importBody = { _, _ -> hold.await() }
        val c = controller()
        val first = show(c)
        c.onImportResult(backupFile())
        assertTrue(pumpUntil { importCalls == 1 })
        leave(c, first)

        hold.complete(plan(ImportMode.MERGE))
        idle()
        val again = show(c)

        assertEquals(View.GONE, progressCard(again).visibility)
    }

    /** Picks a backup over existing points and answers the merge/replace dialog with Replace. */
    private fun startReplace(c: DataSettingsController) {
        existingPoints = List(3) { Mockito.mock(Coordinate::class.java) }
        c.onImportResult(backupFile())
        assertTrue("merge/replace asked", pumpUntil { ShadowDialog.getLatestDialog()?.isShowing == true })
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idle()
    }

    @Test fun `partial-replace confirmation is still asked after the Data view is gone`() {
        var decision: PartialReplaceDecision? = null
        importBody = { replace, confirm ->
            decision = confirm(plan(ImportMode.REPLACE, skipped = 1), 3)
            plan(if (replace) ImportMode.REPLACE else ImportMode.MERGE)
        }
        val c = controller()
        val v = show(c)
        startReplace(c)
        assertTrue(pumpUntil { importCalls == 1 })
        leave(c, v)                                            // user switched category; fragment still there

        assertTrue("asked", pumpUntil { (ShadowDialog.getLatestDialog() as? AlertDialog)?.let { it.isShowing &&
            it.getButton(DialogInterface.BUTTON_POSITIVE)?.text == "Merge instead" } == true })
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        assertTrue(pumpUntil { decision != null })
        assertEquals(PartialReplaceDecision.MERGE_INSTEAD, decision)
    }

    @Test fun `partial-replace confirmation answers CANCEL when the fragment is destroyed with the dialog open`() {
        var decision: PartialReplaceDecision? = null
        importBody = { _, confirm -> decision = confirm(plan(ImportMode.REPLACE, skipped = 1), 3); plan(ImportMode.REPLACE, cancelled = true) }
        val c = controller()
        val v = show(c)
        startReplace(c)
        assertTrue(pumpUntil { (ShadowDialog.getLatestDialog() as? AlertDialog)
            ?.getButton(DialogInterface.BUTTON_POSITIVE)?.text == "Merge instead" })
        val dialog = ShadowDialog.getLatestDialog()
        leave(c, v)

        // e.g. rotation: the fragment is destroyed. Before, the import waited forever for an answer.
        host.activity.supportFragmentManager.beginTransaction().remove(host.fragment).commitNow()

        assertTrue("answered", pumpUntil { decision != null })
        assertEquals("nothing is deleted", PartialReplaceDecision.CANCEL, decision)
        assertFalse("dialog dismissed", dialog.isShowing)
    }

    @Test fun `partial-replace confirmation needed when the fragment is no longer added answers CANCEL without a dialog`() {
        val reachConfirm = CompletableDeferred<Unit>()
        var decision: PartialReplaceDecision? = null
        importBody = { _, confirm -> reachConfirm.await(); decision = confirm(plan(ImportMode.REPLACE, skipped = 1), 3); plan(ImportMode.REPLACE) }
        val c = controller()
        val v = show(c)
        c.onImportResult(backupFile())                         // no existing points: straight to the import
        assertTrue(pumpUntil { importCalls == 1 })
        leave(c, v)
        host.activity.supportFragmentManager.beginTransaction().remove(host.fragment).commitNow()
        val dialogsBefore = ShadowDialog.getLatestDialog()

        reachConfirm.complete(Unit)

        assertTrue(pumpUntil { decision != null })
        assertEquals(PartialReplaceDecision.CANCEL, decision)
        assertTrue("no new dialog", ShadowDialog.getLatestDialog() === dialogsBefore)
        assertNull(dialogsBefore)
    }
}
