package de.mogluco.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Wraps NFC reader mode for Libre3 activation. Libre3 sensors are ISO 15693 (NfcV) tags, so
 * we enable reader mode with [NfcAdapter.FLAG_READER_NFC_V] while the onboarding scan step is
 * visible and emit discovered tags on [tags]. App-wide singleton (held by MoglucoApp) so the
 * Activity can drive enable/disable and the onboarding screen can collect tags.
 */
class NfcController {

    private val _tags = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val tags: SharedFlow<Tag> = _tags.asSharedFlow()

    /** Whether the device has NFC hardware at all (a missing adapter can't be turned on). */
    fun isAvailable(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context) != null

    /** Whether NFC is currently switched on — user-toggleable, so re-check on every resume. */
    fun isEnabled(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    fun enable(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5_000)
        }
        adapter.enableReaderMode(
            activity,
            { tag -> _tags.tryEmit(tag) },
            NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            options,
        )
    }

    fun disable(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }
}
