/* artzig stub. */
package android.media.session;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public final class PlaybackState implements Parcelable {
    public static final int STATE_NONE = 0;
    public static final int STATE_STOPPED = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_FAST_FORWARDING = 4;
    public static final int STATE_REWINDING = 5;
    public static final int STATE_BUFFERING = 6;
    public static final int STATE_ERROR = 7;
    public static final long ACTION_STOP = 1L;
    public static final long ACTION_PAUSE = 1L << 1;
    public static final long ACTION_PLAY = 1L << 2;
    public static final long ACTION_REWIND = 1L << 3;
    public static final long ACTION_SKIP_TO_PREVIOUS = 1L << 4;
    public static final long ACTION_SKIP_TO_NEXT = 1L << 5;
    public static final long ACTION_FAST_FORWARD = 1L << 6;
    public static final long ACTION_SEEK_TO = 1L << 8;
    public static final long ACTION_PLAY_PAUSE = 1L << 9;
    public static final long PLAYBACK_POSITION_UNKNOWN = -1;

    private int mState;
    private long mPosition;
    private float mSpeed;
    private long mActions;

    private PlaybackState() {}
    public int getState() { return mState; }
    public long getPosition() { return mPosition; }
    public float getPlaybackSpeed() { return mSpeed; }
    public long getActions() { return mActions; }
    public long getLastPositionUpdateTime() { return 0; }
    public long getBufferedPosition() { return 0; }
    public CharSequence getErrorMessage() { return null; }
    public java.util.List<CustomAction> getCustomActions() { return new java.util.ArrayList<>(); }
    public long getActiveQueueItemId() { return -1; }
    public Bundle getExtras() { return null; }

    public static final class Builder {
        private final PlaybackState s = new PlaybackState();
        public Builder() {}
        public Builder(PlaybackState from) {}
        public Builder setState(int state, long position, float playbackSpeed) {
            s.mState = state; s.mPosition = position; s.mSpeed = playbackSpeed;
            return this;
        }
        public Builder setState(int state, long position, float playbackSpeed, long updateTime) {
            return setState(state, position, playbackSpeed);
        }
        public Builder setActions(long actions) { s.mActions = actions; return this; }
        public Builder setBufferedPosition(long bufferedPosition) { return this; }
        public Builder setErrorMessage(CharSequence error) { return this; }
        public Builder setActiveQueueItemId(long id) { return this; }
        public Builder setExtras(Bundle extras) { return this; }
        public Builder addCustomAction(CustomAction customAction) { return this; }
        public Builder addCustomAction(String action, String name, int icon) { return this; }
        public PlaybackState build() { return s; }
    }

    public static final class CustomAction implements Parcelable {
        private final String mAction;
        private final CharSequence mName;
        private final int mIcon;
        private final Bundle mExtras;
        private CustomAction(String action, CharSequence name, int icon, Bundle extras) {
            mAction = action; mName = name; mIcon = icon; mExtras = extras;
        }
        public String getAction() { return mAction; }
        public CharSequence getName() { return mName; }
        public int getIcon() { return mIcon; }
        public Bundle getExtras() { return mExtras; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public static final Parcelable.Creator<CustomAction> CREATOR = new Parcelable.Creator<CustomAction>() {
            @Override public CustomAction createFromParcel(Parcel in) { return new CustomAction(null, null, 0, null); }
            @Override public CustomAction[] newArray(int size) { return new CustomAction[size]; }
        };
        public static final class Builder {
            private final String mA;
            private final CharSequence mN;
            private final int mI;
            private Bundle mE;
            public Builder(String action, CharSequence name, int icon) { mA = action; mN = name; mI = icon; }
            public Builder setExtras(Bundle extras) { mE = extras; return this; }
            public CustomAction build() { return new CustomAction(mA, mN, mI, mE); }
        }
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<PlaybackState> CREATOR = new Parcelable.Creator<PlaybackState>() {
        @Override public PlaybackState createFromParcel(Parcel in) { return new PlaybackState(); }
        @Override public PlaybackState[] newArray(int size) { return new PlaybackState[size]; }
    };
}
