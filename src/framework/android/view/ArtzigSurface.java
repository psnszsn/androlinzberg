/* artzig: a Surface backed by a Wayland video layer (always valid). */
package android.view;

public final class ArtzigSurface extends Surface {
    public ArtzigSurface() { super(); }
    @Override public boolean isValid() { return true; }
    @Override public void release() {}
}
