/* artzig stub: dump-priority constants from frameworks/native IServiceManager.aidl. */
package android.os;
public interface IServiceManager {
    int DUMP_FLAG_PRIORITY_CRITICAL = 1 << 0;
    int DUMP_FLAG_PRIORITY_HIGH = 1 << 1;
    int DUMP_FLAG_PRIORITY_NORMAL = 1 << 2;
    int DUMP_FLAG_PRIORITY_DEFAULT = 1 << 3;
    int DUMP_FLAG_PRIORITY_ALL = DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_HIGH
            | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PRIORITY_DEFAULT;
    int DUMP_FLAG_PROTO = 1 << 4;
}
