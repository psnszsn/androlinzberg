/* artzig stub: proto ids. */
package android.view.inputmethod;
public final class InputMethodEditorTraceProto {
    public static final class InputMethodClientsTraceFileProto { public static final long CLIENT = 1; }
    public static final class InputMethodManagerServiceTraceFileProto { public static final long CLIENT = 1; }
    public static final class InputMethodServiceTraceFileProto { public static final long CLIENT = 1; }
    public static final class InputMethodClientsTraceProto {
        public static final long ELAPSED_REALTIME_NANOS = 100, WHERE = 101, CLIENT = 102;
        public static final class ClientSideProto { public static final long DISPLAY_ID = 1, VIEW_ROOT_IMPL = 2, EDITOR_INFO = 3, INPUT_METHOD_MANAGER = 4, INPUT_CONNECTION = 5, INPUT_CONNECTION_CALL = 6, IME_INSETS_SOURCE_CONSUMER = 7, IME_FOCUS_CONTROLLER = 8; }
    }
    private InputMethodEditorTraceProto() {}
}
