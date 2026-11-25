package app.surrealar.util.diagnostics

import android.content.Context
import app.surrealar.SurRealApplicationEntryPoint
import app.surrealar.coords.appGeoidModel
import app.surrealar.domain.model.coordinateEpochOf
import app.surrealar.settings.model.AntennaSetup
import app.surrealar.settings.model.VerticalReference
import app.surrealar.util.diagnostics.report.SurveyContextCollector
import dagger.hilt.android.EntryPointAccessors
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.geoid.Geoid18Model
import io.github.pennstatecs.geokit.geoid.Geoid2022Model
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EmlidNmeaDiagnostics
import kotlinx.coroutines.flow.first

/**
 * Gathers [SurveyContextCollector.Input] from the running app (Hilt entry point; reads existing state only) for
 * survey-context.txt. Every part is read on its own: one failing source leaves "unknown", never the section.
 * Collects no coordinates, host/IP, credentials or position history.
 */
object SurveyContextGatherer {

    suspend fun collect(context: Context): String = try {
        SurveyContextCollector.render(gather(context))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "=== Survey context ===\n(unavailable: ${e.javaClass.simpleName})\n"
    }

    private inline fun <T> safe(read: () -> T): T? = try { read() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    private suspend fun gather(context: Context): SurveyContextCollector.Input {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, SurRealApplicationEntryPoint::class.java)
        val repo = ep.settingsRepository()
        val css = safe { ep.coordinateSystemService() }
        val profile = safe { repo.externalReceiverProfile.first() }
        val receiver = safe { repo.gnssReceiverSettings.first() }
        val coords = safe { repo.coordinateSystemSettings.first() } ?: css?.settings
        val tilt = safe { ep.receiverTiltMonitor().status.value }
        val deviceInfo = safe { ep.receiverDeviceManager().deviceInfo.value }
        val fix = safe { ep.fixSwitchboard().currentFix.value }
        val source = safe { ep.externalNmeaSource().let { it.nmeaTimingStats() to it.nmeaExtraStats() } }
        val setup = receiver?.let { r -> safe { AntennaSetup.resolve(r, profile) } }
        val nowMs = System.currentTimeMillis()

        val counts = buildList {
            source?.first?.let { t ->
                add("GGA" to t.ggaCount.toString()); add("RMC" to t.rmcCount.toString())
                add("GST" to t.gstCount.toString()); add("ZDA" to t.zdaCount.toString())
                add("GSA" to t.gsaCountByTalker.values.sum().toString()); add("GSV" to t.gsvCountByTalker.values.sum().toString())
                add("Emitted fixes" to t.emittedFixCount.toString())
            }
            source?.second?.let { x ->
                val e = EmlidNmeaDiagnostics.from(x)
                add("EBP" to e.ebpCount.toString()); add("ETC" to e.etcCount.toString())
            }
        }

        val vertical = coords?.effectiveVertical
        val geoidId = vertical?.let { if (it == VerticalReference.NAVD88_GEOID18) GeoidModelId.GEOID18 else it.appGeoidModel() }
        val geoidVersion = when (geoidId) {
            GeoidModelId.GEOID18 -> safe { Geoid18Model.INFO.dataVersion }
            GeoidModelId.EGM2008 -> safe { GlobalGeoidModels.EGM2008_INFO.dataVersion }
            GeoidModelId.EGM96 -> safe { GlobalGeoidModels.EGM96_INFO.dataVersion }
            GeoidModelId.GEOID2022 -> safe { Geoid2022Model.INFO.dataVersion }
            else -> if (vertical == VerticalReference.RECEIVER_MSL) "receiver's own geoid (model not reported in NMEA)" else "none"
        }

        return SurveyContextCollector.Input(
            activeSource = safe { ep.sourceSettings().activeProvider.value.name },
            receiverProfile = profile?.label,
            receiverModel = deviceInfo?.model,
            connectionType = safe { repo.externalConnType.first().name },
            receiverFrame = coords?.externalReceiverDatum?.name,
            compensatedOutput = tilt?.compensatedOutput?.label ?: safe { repo.compensatedNmeaOutput.first().label },
            firmware = deviceInfo?.firmware,
            fix = fix?.let { f ->
                SurveyContextCollector.FixQuality(
                    provider = f.provider.name,
                    fixType = f.rtkStatus.name,
                    coordinateEpoch = safe { coordinateEpochOf(f.timeUtc.toEpochMilli()) },
                    timestampSource = f.timestampSource.name,
                    correctionAgeS = f.diffAgeS,
                    hAccM = f.hAccM, vAccM = f.vAccM,
                    satsUsed = f.satsUsed, satsVisible = f.satsVisible,
                    hdop = f.hDop, pdop = f.pDop,
                    ageS = ((nowMs - f.timeUtc.toEpochMilli()) / 1000L).takeIf { it in 0..86_400 },
                )
            },
            sentenceCounts = counts,
            nmeaLinesPerSecond = safe { ep.diagnosticsService().diagnosticData.value.linesPerSecond },
            tilt = tilt,
            poleHeightM = receiver?.poleHeightM,
            phaseCenterOffsetM = setup?.phaseCenterOffsetM,
            phaseCenterFromProfile = setup?.offsetFromProfile,
            cameraBelowReceiverM = receiver?.cameraBelowReceiverM,
            captureOffsetM = setup?.captureOffsetM,
            interruptions = CaptureInterruptions.snapshot(),
            horizontal = coords?.effectiveHorizontal?.label,
            vertical = vertical?.label,
            outputDatum = coords?.datum?.label,
            betaPreview = coords?.nsrs2022Preview,
            betaSuspended = buildList {
                if (coords?.betaHorizontalSuspended == true) add("horizontal system")
                if (coords?.betaDatumSuspended == true) add("output datum")
                if (coords?.betaVerticalSuspended == true) add("height reference")
            },
            geoidModel = geoidId?.token ?: vertical?.shortLabel,
            geoidVersion = geoidVersion,
            geoidLoadState = css?.heightModelState(),
            transformationPaths = transformationPaths(coords?.nsrs2022Preview == true),
            gridFailures = css?.gridLoadFailures().orEmpty(),
        )
    }

    /** Status of the frame paths the app uses (legacy) and, with the preview, the NSRS 2022 beta paths. */
    private fun transformationPaths(preview: Boolean): List<String> = buildList {
        val epoch = CoordinateEpoch(2025.0)
        val from = ReferenceFrame.WGS84_AS_ITRF2014.id
        for (to in listOf(ReferenceFrame.NAD83_2011.id, ReferenceFrame.ITRF2020.id)) {
            safe { FrameTransformationRegistry.DEFAULT.find(from, to, epoch).status }
                ?.let { add("legacy  $from -> $to : $it") }
        }
        safe { app.surrealar.coords.Nsrs2022Capabilities.all(app.surrealar.coords.Nsrs2022Registries.frames(preview), app.surrealar.coords.Nsrs2022Registries.crs(preview)) }
            ?.forEach { c -> add("${if (preview) "beta   " else "default"} ${c.name} : ${c.state.label}${if (c.selectable) " (offered)" else ""}") }
    }
}
