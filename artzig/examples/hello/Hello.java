import java.util.List;
public class Hello {
  record Point(int x, int y) {}
  public static void main(String[] args) {
    var p = new Point(1, 2);
    System.out.println("Hello from ART: " + p + " java.version=" + System.getProperty("java.version")
        + " " + List.of("a", "b").stream().map(String::toUpperCase).toList()
        + " " + java.time.LocalDate.of(2026, 8, 23).getDayOfWeek()
        + " virtual-libc=" + new java.io.File("/system/lib/libc.so").exists());
  }
}
