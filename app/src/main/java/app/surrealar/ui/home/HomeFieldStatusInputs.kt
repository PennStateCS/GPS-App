package app.surrealar.ui.home

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.diagnostics.DiagnosticData
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.settings.model.ExternalReceiverSettings
import io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyValidity
import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot

/**
 * The Home Field Status integration path, minus Android: turns the live values HomeFragment collects (fix snapshot +
 * its provider, selected source, saved receiver, selection core state, tilt status, diagnostics) into
 * [HomeFieldStatusMapper.Inputs]. Kept out of the fragment so tests can drive the real path with real
 * [ReceiverSelectionState] / [TiltStatus] values.
 *
 * Rules: the live source comes from [HomeLiveSource] (never from the persisted selection alone); receiver-reported
 * identity is used only when it is confirmed for the receiver producing the live fix; the RS4 position reference is
 * attached only for an external selection.
 */
object HomeFieldStatusInputs {

    fun build(
        snapshot: FixSnapshot,
        fixProvider: Provider?,
        source: LocationSourceType,
        receiver: ExternalReceiverSettings,
        diag: DiagnosticData,
        sky: SkySnapshot,
        conn: ReceiverSelectionState?,
        tilt: TiltStatus?,
        recentParseErrors: Long,
        /**
         * The receiver-selection core's attribution of the live fix ([app.surrealar.receiver.selection.LiveSourceState]).
         * When given, an external fix counts as the selected receiver's only if the core attributes it to the
         * connected receiver (endpoint == saved receiver, link CONNECTED, after the last switch fence): this tells two
         * external receivers apart, which the fix's provider alone cannot. Null = provider-based rules only.
         */
        liveSource: app.surrealar.receiver.selection.LiveSourceState? = null,
        /**
         * The toolbar pipeline's stabilised correction state (shared with the toolbar and the report), used only when
         * it was computed for this same source, receiver and live provider ([HomeToolbarCorrection.forHome]).
         */
        toolbarCorrection: HomeToolbarCorrection? = null,
    ): HomeFieldStatusMapper.Inputs {
        val externalSelected = source != LocationSourceType.INTERNAL
        val savedHost = receiver.tcpHost.takeIf { it.isNotBlank() }
        val lat = snapshot.lat
        val lon = snapshot.lon
        // Fix liveness = valid coordinates, NOT the fix's GNSS timestamp vs the device clock (which can be skewed).
        val fixValid = lat != null && lon != null && !lat.isNaN() && !lon.isNaN() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
        val savedTargetAttempt = when (val a = conn?.attempt) {
            is ConnectionAttempt.Testing -> conn.savedTarget == null || a.target == conn.savedTarget
            is ConnectionAttempt.Switching -> conn.savedTarget == null || a.target == conn.savedTarget
            else -> false
        }
        val resolvedByProvider = HomeLiveSource.resolve(
            externalSelected = externalSelected,
            fixProvider = fixProvider,
            fixValid = fixValid,
            externalProviderActive = conn?.activeProvider == ProviderChoice.EXTERNAL_TCP,
            link = conn?.link,
            attemptInProgress = savedTargetAttempt,
            identityHost = conn?.identity?.host,
            savedHost = savedHost,
        )
        val coreExternal = liveSource?.takeIf { it.kind == app.surrealar.receiver.selection.LiveSourceKind.EXTERNAL }?.external
        // An external fix the core does not attribute to the connected receiver (e.g. receiver A's last fix while
        // switching to B) is not the selected receiver's position.
        val resolved = if (liveSource != null && resolvedByProvider.live == HomeLiveSource.Live.EXTERNAL && coreExternal == null)
            resolvedByProvider.copy(live = HomeLiveSource.Live.NONE, identityConfirmed = false)
        else resolvedByProvider
        val identity = if (liveSource != null) coreExternal?.identity
            else conn?.identity?.takeIf { resolved.identityConfirmed }
        val reportedName = identity?.name?.takeIf { it.isNotBlank() }
        val model = identity?.takeIf { it.modelVerified }?.let { it.recognizedProfile?.label ?: it.reportedModel }
        val externalLive = resolved.live == HomeLiveSource.Live.EXTERNAL
        val matchedCorrection = toolbarCorrection?.takeIf { externalLive }
            ?.forHome(source, app.surrealar.ui.toolbar.toolbarReceiverKey(receiver), fixProvider)
        val vAcc = snapshot.verticalAccuracyM
        return HomeFieldStatusMapper.Inputs(
            isInternal = !externalSelected,
            externalLabel = receiver.profile.shortLabel,
            receiverName = reportedName ?: receiver.displayName,
            connectionAddress = savedHost?.let { "$it:${receiver.tcpPort}" },
            rtkStatus = RtkStatus.fromPrefKey(snapshot.rtkStatus),
            hAccM = snapshot.horizontalAccuracyM,
            vAccM = vAcc,
            satsUsed = snapshot.satsUsed,
            satsVisible = snapshot.satellitesInView,
            skyTotalUsed = sky.totalUsed,
            skyTotalVisible = sky.totalVisible,
            hdop = snapshot.hdop,
            pdop = snapshot.pDop,
            correctionAgeS = snapshot.correctionAgeS,
            correctionStationId = snapshot.correctionStationId,
            // Real live NMEA stream rate from the DiagnosticsService singleton (the source the pipeline feeds).
            nmeaLinesPerSecond = diag.linesPerSecond,
            nmeaParseErrors = recentParseErrors,
            hasRecentFix = fixValid,
            receiverModel = model,
            vAccInvalid = externalLive && vAcc != null &&
                (vAcc >= VerticalAccuracyValidity.INVALID_AT_OR_ABOVE_M || vAcc <= 0.0),
            positionReference = if (!externalSelected || tilt == null) null else
                HomeFieldStatusMapper.positionReferenceOf(tilt.applicable, tilt.reference, tilt.compensatedOutput),
            // Fix.hAccM is the receiver's own report (GST / $..ACCURACY); Home never substitutes an estimate.
            hAccEstimated = false,
            live = resolved.live,
            link = resolved.link,
            correctionHealth = matchedCorrection?.health,
            correctionRecovering = matchedCorrection?.recovering == true,
        )
    }
}
