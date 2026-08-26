/* artzig stub: desktop host exposes one fixed local audio route. */
package android.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RouteDiscoveryPreference implements android.os.Parcelable {
    private final List<String> mPreferredFeatures;
    private final boolean mActiveScan;

    RouteDiscoveryPreference(List<String> preferredFeatures, boolean activeScan) {
        mPreferredFeatures = Collections.unmodifiableList(new ArrayList<>(preferredFeatures));
        mActiveScan = activeScan;
    }

    public List<String> getPreferredFeatures() { return mPreferredFeatures; }
    public boolean shouldPerformActiveScan() { return mActiveScan; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}

    public static final android.os.Parcelable.Creator<RouteDiscoveryPreference> CREATOR =
            new android.os.Parcelable.Creator<RouteDiscoveryPreference>() {
        public RouteDiscoveryPreference createFromParcel(android.os.Parcel in) {
            return new RouteDiscoveryPreference(Collections.emptyList(), false);
        }
        public RouteDiscoveryPreference[] newArray(int size) {
            return new RouteDiscoveryPreference[size];
        }
    };

    public static final class Builder {
        private List<String> mPreferredFeatures;
        private boolean mActiveScan;

        public Builder(List<String> preferredFeatures, boolean activeScan) {
            mPreferredFeatures = new ArrayList<>(preferredFeatures);
            mActiveScan = activeScan;
        }

        public Builder(RouteDiscoveryPreference preference) {
            this(preference.getPreferredFeatures(), preference.shouldPerformActiveScan());
        }

        public Builder setPreferredFeatures(List<String> preferredFeatures) {
            mPreferredFeatures = new ArrayList<>(preferredFeatures);
            return this;
        }

        public Builder setShouldPerformActiveScan(boolean activeScan) {
            mActiveScan = activeScan;
            return this;
        }

        public RouteDiscoveryPreference build() {
            return new RouteDiscoveryPreference(mPreferredFeatures, mActiveScan);
        }
    }
}
