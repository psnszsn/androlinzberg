/* artzig stub: date picker (real widget excluded — calendar UI unneeded). */
package android.widget;
public class DatePicker extends FrameLayout {
    public DatePicker(android.content.Context context) { super(context); }
    public interface OnDateChangedListener {
        void onDateChanged(DatePicker view, int year, int monthOfYear, int dayOfMonth);
    }
    public int getYear() { return 2026; }
    public int getMonth() { return 0; }
    public int getDayOfMonth() { return 1; }
    public void updateDate(int year, int month, int dayOfMonth) {}
    public void init(int year, int monthOfYear, int dayOfMonth, OnDateChangedListener listener) {}
}
