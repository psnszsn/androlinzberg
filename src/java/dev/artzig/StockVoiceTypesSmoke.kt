package dev.artzig

import android.hardware.soundtrigger.KeyphraseMetadata
import android.os.Binder
import android.os.Parcel
import android.os.Parcelable
import android.service.voice.FailureSuggestedAction
import android.service.voice.HotwordDetectionServiceFailure
import android.service.voice.HotwordRejectedResult
import android.service.voice.VisibleActivityInfo
import android.service.voice.VisualQueryAttentionResult
import java.util.Locale

/** Stock assistant identity and result contracts; no recognizer or DSP fixture. */
internal object StockVoiceTypesSmoke {
    fun run() {
        val token = Binder()
        val info = VisibleActivityInfo(42, token)
        val restored = copy(info, VisibleActivityInfo.CREATOR)
        check(restored == info && restored.hashCode() == info.hashCode())
        check(restored.activityId.taskId == 42 && restored.activityId.assistToken === token)
        check(restored.activityId == info.activityId)
        check(restored.activityId.hashCode() == info.activityId.hashCode())
        check(info.activityId != VisibleActivityInfo(43, token).activityId)
        check(info.activityId != VisibleActivityInfo(42, Binder()).activityId)
        val constructor = VisibleActivityInfo::class.java.getConstructor(
            Int::class.javaPrimitiveType, android.os.IBinder::class.java)
        check(runCatching { constructor.newInstance(42, null) }.exceptionOrNull()?.cause is NullPointerException)
        val invalid = Parcel.obtain()
        try {
            invalid.writeInt(42)
            invalid.writeStrongBinder(null)
            invalid.setDataPosition(0)
            check(runCatching { VisibleActivityInfo.CREATOR.createFromParcel(invalid) }
                .exceptionOrNull() is NullPointerException)
        } finally { invalid.recycle() }

        val locales = mutableSetOf(Locale.US)
        val phrase = KeyphraseMetadata(7, "Hello Device", locales, 3)
        locales.clear()
        check(phrase.supportsPhrase("HELLO DEVICE") && !phrase.supportsPhrase("other"))
        check(phrase.supportsLocale(Locale.US) && !phrase.supportsLocale(Locale.FRANCE))
        check(copy(phrase, KeyphraseMetadata.CREATOR) == phrase)
        val wildcard = KeyphraseMetadata(8, "", emptySet(), 1)
        check(wildcard.supportsPhrase("any") && wildcard.supportsLocale(Locale.FRANCE))

        val rejected = HotwordRejectedResult.Builder()
            .setConfidenceLevel(HotwordRejectedResult.CONFIDENCE_LEVEL_HIGH).build()
        check(copy(rejected, HotwordRejectedResult.CREATOR) == rejected)
        check(HotwordRejectedResult.Builder().build().confidenceLevel ==
            HotwordRejectedResult.CONFIDENCE_LEVEL_NONE)
        val failure = HotwordDetectionServiceFailure(
            HotwordDetectionServiceFailure.ERROR_CODE_BINDING_DIED, "engine exited")
        val failureCopy = copy(failure, HotwordDetectionServiceFailure.CREATOR)
        check(failureCopy.errorCode == failure.errorCode && failureCopy.errorMessage == "engine exited")
        check(failureCopy.suggestedAction == FailureSuggestedAction.RECREATE_DETECTOR)
        check(HotwordDetectionServiceFailure(HotwordDetectionServiceFailure.ERROR_CODE_DETECT_TIMEOUT,
            "timeout").suggestedAction == FailureSuggestedAction.RESTART_RECOGNITION)
        check(runCatching { HotwordDetectionServiceFailure(0, "") }.exceptionOrNull() is IllegalArgumentException)

        val attention = VisualQueryAttentionResult.Builder().setEngagementLevel(80)
            .setInteractionIntention(VisualQueryAttentionResult.INTERACTION_INTENTION_VISUAL_ACCESSIBILITY)
            .build()
        check(copy(attention, VisualQueryAttentionResult.CREATOR) == attention)
        check(attention.buildUpon().setEngagementLevel(90).build().engagementLevel == 90)
        check(attention.engagementLevel == 80)
        for (level in listOf(0, 101)) {
            // Pinned AnnotationValidations uses IllegalStateException for @IntRange.
            val failure = runCatching {
                VisualQueryAttentionResult.Builder().setEngagementLevel(level).build()
            }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message!!.startsWith("Invalid @IntRange"))
        }
        check(runCatching { VisualQueryAttentionResult.Builder().setInteractionIntention(-1).build() }
            .exceptionOrNull() is IllegalArgumentException)
        println("stock-voice-types: assistant identity, Parcel validation, keyphrase matching and result contracts passed")
    }

    private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
