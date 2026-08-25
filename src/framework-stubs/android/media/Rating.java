/* artzig stub. */
package android.media;
import android.os.Parcel;
import android.os.Parcelable;
public final class Rating implements Parcelable {
    public static final int RATING_NONE = 0;
    public static final int RATING_HEART = 1;
    public static final int RATING_THUMB_UP_DOWN = 2;
    public static final int RATING_3_STARS = 3;
    public static final int RATING_4_STARS = 4;
    public static final int RATING_5_STARS = 5;
    public static final int RATING_PERCENTAGE = 6;
    private Rating() {}
    public static Rating newUnratedRating(int ratingStyle) { return new Rating(); }
    public static Rating newHeartRating(boolean hasHeart) { return new Rating(); }
    public static Rating newThumbRating(boolean thumbIsUp) { return new Rating(); }
    public static Rating newStarRating(int starRatingStyle, float starRating) { return new Rating(); }
    public static Rating newPercentageRating(float percent) { return new Rating(); }
    public boolean isRated() { return false; }
    public int getRatingStyle() { return RATING_NONE; }
    public boolean hasHeart() { return false; }
    public boolean isThumbUp() { return false; }
    public float getStarRating() { return 0; }
    public float getPercentRating() { return 0; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<Rating> CREATOR = new Parcelable.Creator<Rating>() {
        @Override public Rating createFromParcel(Parcel in) { return new Rating(); }
        @Override public Rating[] newArray(int size) { return new Rating[size]; }
    };
}
