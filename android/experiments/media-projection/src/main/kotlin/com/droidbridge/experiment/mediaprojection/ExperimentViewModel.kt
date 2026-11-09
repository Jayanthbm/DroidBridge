package com.droidbridge.experiment.mediaprojection

import android.content.Intent
import androidx.lifecycle.ViewModel

/**
 * Holds the last consent grant and the pending start mode across configuration changes.
 *
 * Found on the S24 Ultra: rotating the device recreated the activity, the plain fields were lost,
 * and the token-reuse test reported "no token captured yet" even though a session had just run.
 * A ViewModel is the right scope for state that belongs to the screen but must outlive a rotation.
 *
 * The token is retained **only** so the one-shot rule can be tested deliberately. It is never
 * replayed to start a second session; `getMediaProjection` with it is expected to throw.
 */
class ExperimentViewModel : ViewModel() {
    var lastResultCode: Int = 0
    var lastConsentData: Intent? = null
    var pendingMode: String = ProjectionService.MODE_ENCODE
}
