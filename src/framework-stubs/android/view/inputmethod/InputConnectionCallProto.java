/* artzig stub: proto ids. */
package android.view.inputmethod;
public final class InputConnectionCallProto {
    public static final long NAME = 1;
    public static final long GET_TEXT_BEFORE_CURSOR = 2, GET_TEXT_AFTER_CURSOR = 3,
            GET_SELECTED_TEXT = 4, GET_SURROUNDING_TEXT = 5, GET_CURSOR_CAPS_MODE = 6,
            GET_EXTRACTED_TEXT = 7;
    public static final class GET {
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    public static final class GetCursorCapsMode {
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    public static final class GetExtractedText {
        public static final class ExtractedTextRequest {
            public static final long TOKEN = 101, FLAGS = 102, HINT_MAX_LINES = 103, HINT_MAX_CHARS = 104;
        }
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    public static final class GetSelectedText {
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    public static final class GetSurroundingText {
        public static final class SurroundingText {
            public static final long TEXT = 201, SELECTION_START = 202, SELECTION_END = 203, OFFSET = 204;
        }
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    public static final class GetTextAfterCursor {
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    public static final class GetTextBeforeCursor {
        public static final long AFTER_LENGTH = 1;
        public static final long BEFORE_LENGTH = 2;
        public static final long E = 3;
        public static final long FLAGS = 4;
        public static final long LENGTH = 5;
        public static final long REQUEST = 6;
        public static final long REQ_MODES = 7;
        public static final long RESULT = 8;
        public static final long S = 9;
    }
    private InputConnectionCallProto() {}
}
