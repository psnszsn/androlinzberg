package dev.artzig

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import org.chromium.net.RequestContextConfigOptions
import org.chromium.net.httpflags.BaseFeatureOverrides
import org.chromium.net.httpflags.FlagValue
import org.chromium.net.httpflags.Flags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Wire contracts consumed by the pinned Cronet configuration and HTTP flag loaders. */
internal object StockCronetConfigSmoke {
    fun run() {
        val defaults = RequestContextConfigOptions.getDefaultInstance()
        check(!defaults.hasQuicEnabled() && !defaults.quicEnabled)
        val builder = defaults.toBuilder().setUserAgent("fixture/1")
            .setQuicEnabled(false).setHttpCacheMaxSize(1L shl 34)
        val first = builder.build()
        builder.setUserAgent("fixture/2").setQuicEnabled(true)
        check(first.userAgent == "fixture/1" && first.hasQuicEnabled() && !first.quicEnabled)
        check(RequestContextConfigOptions.parseFrom(first.toByteArray()) == first)
        // A newer producer's unknown field survives an older client's decode/encode.
        val unknownField = byteArrayOf(-8, 7, 1) // field 127, varint 1
        val extendedWire = first.toByteArray() + unknownField
        check(RequestContextConfigOptions.parseFrom(extendedWire).toByteArray().contentEquals(extendedWire))
        val malformed = runCatching { RequestContextConfigOptions.parseFrom(byteArrayOf(10, 5, 65)) }.exceptionOrNull()
        check(malformed is InvalidProtocolBufferException)

        val choice = FlagValue.ConstrainedValue.newBuilder().setAppId("dev.example")
            .setBoolValue(true).setIntValue(0).build()
        check(choice.hasIntValue() && !choice.hasBoolValue() && choice.intValue == 0L)
        val value = FlagValue.newBuilder().addConstrainedValues(choice).build()
        val flagsBuilder = Flags.newBuilder().putFlags("Cronet_fixture", value)
        val flags = flagsBuilder.build()
        flagsBuilder.clearFlags()
        check(flags.getFlagsOrThrow("Cronet_fixture").constrainedValuesCount == 1)
        val stream = ByteArrayOutputStream()
        flags.writeDelimitedTo(stream)
        Flags.getDefaultInstance().writeDelimitedTo(stream)
        val input = ByteArrayInputStream(stream.toByteArray())
        check(Flags.parseDelimitedFrom(input) == flags)
        check(Flags.parseDelimitedFrom(input) == Flags.getDefaultInstance())
        check(Flags.parseDelimitedFrom(input) == null)

        val bytes = byteArrayOf(0, -1, 65)
        val retainedBytes = ByteString.copyFrom(bytes)
        bytes[0] = 42
        val state = BaseFeatureOverrides.FeatureState.newBuilder().setEnabled(false)
            .putParams("opaque", retainedBytes).build()
        val overrides = BaseFeatureOverrides.newBuilder().putFeatureStates("Fixture", state).build()
        val decoded = BaseFeatureOverrides.parseFrom(overrides.toByteArray())
            .getFeatureStatesOrThrow("Fixture")
        check(decoded.hasEnabled() && !decoded.enabled)
        check(decoded.getParamsOrThrow("opaque").toByteArray().contentEquals(byteArrayOf(0, -1, 65)))
        println("stock Cronet configuration wire contracts: PASS")
    }
}
