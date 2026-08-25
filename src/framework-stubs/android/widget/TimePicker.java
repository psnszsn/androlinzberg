/* artzig stub. */
package android.widget;
public class TimePicker extends FrameLayout {
    public TimePicker(android.content.Context context) { super(context); }
    public interface OnTimeChangedListener {
        void onTimeChanged(TimePicker view, int hourOfDay, int minute);
    }
    public int getHour() { return 0; }
    public int getMinute() { return 0; }
    public void setHour(int hour) {}
    public void setMinute(int minute) {}
    public void setIs24HourView(Boolean is24HourView) {}
    public boolean is24HourView() { return true; }
    public void setOnTimeChangedListener(OnTimeChangedListener listener) {}
}
