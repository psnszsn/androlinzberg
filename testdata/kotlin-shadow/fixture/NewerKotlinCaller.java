package fixture;

/** Models APK bytecode calling an API that exists only in its bundled runtime. */
public final class NewerKotlinCaller {
    public static String callNewRuntimeApi() {
        return kotlin.KotlinVersion.appRuntimeMarker();
    }
}
