/* artzig stub: the real class reads device aconfig protos
 * (android.aconfig.DeviceProtos, not vendored), so no flag values exist here
 * and getFlagValue is always null. skipCurrentElement keeps the real class's
 * decision logic verbatim: an undefined flag evaluates to false, so an
 * element gated on "flag" is skipped and one gated on "!flag" is kept —
 * exactly a device with none of these flags released. (Treating every flag
 * as enabled instead makes framework-res.apk unparseable: its manifest
 * declares permission pairs under "flag"/"!flag" and both would survive as
 * duplicates.) */
package com.android.internal.pm.pkg.component;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import org.xmlpull.v1.XmlPullParser;
public class AconfigFlags {
    private static final String ANDROID_RES_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    public AconfigFlags() {}

    public Boolean getFlagValue(String flagPackageAndName) { return null; }

    public boolean skipCurrentElement(ParsingPackage pkg, XmlPullParser parser) {
        return skipCurrentElement(pkg, parser, false);
    }

    public boolean skipCurrentElement(ParsingPackage pkg, XmlPullParser parser,
            boolean allowNoNamespace) {
        String featureFlag = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "featureFlag");
        if (featureFlag == null && allowNoNamespace) {
            featureFlag = parser.getAttributeValue(null, "featureFlag");
        }
        if (featureFlag == null) {
            return false;
        }
        featureFlag = featureFlag.strip();
        boolean negated = false;
        if (featureFlag.startsWith("!")) {
            negated = true;
            featureFlag = featureFlag.substring(1).strip();
        }
        Boolean flagValue = getFlagValue(featureFlag);
        boolean value = flagValue != null && flagValue.booleanValue();
        return value == negated;
    }

    public void addFlagValuesForTesting(java.util.Map<String, Boolean> flagValues) {}
}
