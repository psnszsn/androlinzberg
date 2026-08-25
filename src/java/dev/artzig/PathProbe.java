package dev.artzig;
public final class PathProbe {
    public static void main(String[] args) {
        android.graphics.Path p = new android.graphics.Path();
        try {
            System.out.println("fresh fillType=" + p.getFillType());
        } catch (Throwable t) {
            System.out.println("fresh fillType FAILED: " + t);
        }
        p.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        try {
            System.out.println("after set fillType=" + p.getFillType());
        } catch (Throwable t) {
            System.out.println("after set FAILED: " + t);
        }
        p.moveTo(0, 0);
        p.lineTo(10, 10);
        p.reset();
        System.out.println("reset ok, isEmpty=" + p.isEmpty());
    }
}
