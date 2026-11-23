package app.surrealar.ui.models.readiness

import app.surrealar.ui.models.ModelCardInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadinessStyleTest {

    private fun report(v: Verdict, vararg checks: ReadinessCheck) = ReadinessReport(v, checks.toList(), "")

    @Test
    fun cardVerdictsComeFromReports() {
        val map = ModelCardInfo.verdicts(
            mapOf("a" to report(Verdict.READY), "b" to report(Verdict.NOT_READY)),
        )
        assertEquals(mapOf("a" to Verdict.READY, "b" to Verdict.NOT_READY), map)
    }

    @Test
    fun chipLabels() {
        assertEquals("Ready", ReadinessStyle.verdictChip(Verdict.READY))
        assertEquals("Warnings", ReadinessStyle.verdictChip(Verdict.READY_WITH_WARNINGS))
        assertEquals("Not ready", ReadinessStyle.verdictChip(Verdict.NOT_READY))
        assertEquals("Ready for AR", ReadinessStyle.verdictTitle(Verdict.READY))
    }

    @Test
    fun fixButtonsOnlyForActions() {
        assertNull(ReadinessStyle.fixButton(FixAction.NONE))
        assertEquals("Use Bottom Center", ReadinessStyle.fixButton(FixAction.USE_BOTTOM_CENTER))
        assertEquals("Link a point", ReadinessStyle.fixButton(FixAction.LINK_POINT))
    }

    @Test
    fun fixAppliedMessageNamesOnePointOrCounts() {
        assertEquals("Placement updated for ‘Point 5’", ReadinessStyle.fixAppliedMessage(listOf("Point 5")))
        assertEquals("Placement updated for 3 points", ReadinessStyle.fixAppliedMessage(listOf("a", "b", "c")))
    }

    @Test
    fun logLineListsFailAndWarnIds() {
        val r = report(
            Verdict.NOT_READY,
            ReadinessCheck("file", "t", CheckStatus.FAIL, "d"),
            ReadinessCheck("placement", "t", CheckStatus.WARN, "d"),
            ReadinessCheck("units", "t", CheckStatus.WARN, "d"),
            ReadinessCheck("links", "t", CheckStatus.PASS, "d"),
        )
        assertEquals(
            "MODEL_READINESS model=${app.surrealar.util.diagnostics.LogPrivacy.file("shed.glb")} verdict=NOT_READY fails=[file] warns=[placement,units]",
            ReadinessStyle.logLine("shed.glb", r),
        )
        assertEquals(
            "MODEL_READINESS model=${app.surrealar.util.diagnostics.LogPrivacy.file("x.glb")} verdict=READY fails=[] warns=[]",
            ReadinessStyle.logLine("x.glb", report(Verdict.READY)),
        )
    }
}
