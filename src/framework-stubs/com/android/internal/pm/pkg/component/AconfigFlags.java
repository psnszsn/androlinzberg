/* artzig stub: the real class reads device aconfig protos
 * (android.aconfig.DeviceProtos, not vendored). No flag values exist here, so
 * nothing is ever skipped and manifest featureFlag attributes are treated as
 * enabled — matching a device with every flag at its default. */
package com.android.internal.pm.pkg.component;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import org.xmlpull.v1.XmlPullParser;
public class AconfigFlags {
    public AconfigFlags() {}
    public Boolean getFlagValue(String flagPackageAndName) { return null; }
    public boolean skipCurrentElement(ParsingPackage pkg, XmlPullParser parser) { return false; }
    public boolean skipCurrentElement(ParsingPackage pkg, XmlPullParser parser, boolean allowNoNamespace) {
        return false;
    }
    public void addFlagValuesForTesting(java.util.Map<String, Boolean> flagValues) {}
}
