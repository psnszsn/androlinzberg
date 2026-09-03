/* artzig stub: string keys of updatable device-policy resources (no device
 * policy exists here; ApplicationPackageManager falls back to defaults). */
package android.app.admin;
public final class DevicePolicyResources {
    public static final String UNDEFINED = "UNDEFINED";
    public static final class Drawables {
        public static final String WORK_PROFILE_ICON_BADGE = "WORK_PROFILE_ICON_BADGE";
        public static final String WORK_PROFILE_ICON = "WORK_PROFILE_ICON";
        private Drawables() {}
        public static final class Style {
            public static final String SOLID_COLORED = "SOLID_COLORED";
            public static final String SOLID_NOT_COLORED = "SOLID_NOT_COLORED";
            private Style() {}
        }
        public static final class Source {
            public static final String UNDEFINED = "UNDEFINED";
            public static final String NOTIFICATION = "NOTIFICATION";
            public static final String PROFILE_SWITCH_ANIMATION = "PROFILE_SWITCH_ANIMATION";
            public static final String HOME_WIDGET = "HOME_WIDGET";
            public static final String LAUNCHER_OFF_BUTTON = "LAUNCHER_OFF_BUTTON";
            public static final String QUICK_SETTINGS = "QUICK_SETTINGS";
            public static final String STATUS_BAR = "STATUS_BAR";
            private Source() {}
        }
    }
    private DevicePolicyResources() {}
}
