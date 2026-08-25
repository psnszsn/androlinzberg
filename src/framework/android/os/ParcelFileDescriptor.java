/* artzig shim: android.os.ParcelFileDescriptor — wraps FileDescriptor via libcore Os. */
package android.os;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
public class ParcelFileDescriptor implements Parcelable, Closeable {
    public static final int MODE_READ_ONLY = 0x10000000, MODE_WRITE_ONLY = 0x20000000,
            MODE_READ_WRITE = 0x30000000, MODE_CREATE = 0x08000000, MODE_TRUNCATE = 0x04000000,
            MODE_APPEND = 0x02000000;
    private final FileDescriptor mFd;
    private boolean mClosed;
    public ParcelFileDescriptor(FileDescriptor fd) { mFd = fd; }
    public static ParcelFileDescriptor open(File file, int mode) throws IOException {
        int flags = 0;
        if ((mode & MODE_READ_WRITE) == MODE_READ_WRITE) flags = OsConstants.O_RDWR;
        else if ((mode & MODE_WRITE_ONLY) != 0) flags = OsConstants.O_WRONLY;
        else flags = OsConstants.O_RDONLY;
        if ((mode & MODE_CREATE) != 0) flags |= OsConstants.O_CREAT;
        if ((mode & MODE_TRUNCATE) != 0) flags |= OsConstants.O_TRUNC;
        if ((mode & MODE_APPEND) != 0) flags |= OsConstants.O_APPEND;
        try {
            return new ParcelFileDescriptor(Os.open(file.getAbsolutePath(), flags, 0600));
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }
    public static ParcelFileDescriptor dup(FileDescriptor orig) throws IOException {
        try { return new ParcelFileDescriptor(Os.dup(orig)); }
        catch (ErrnoException e) { throw new IOException(e); }
    }
    public ParcelFileDescriptor dup() throws IOException { return dup(mFd); }
    public static ParcelFileDescriptor fromFd(int fd) throws IOException {
        FileDescriptor f = new FileDescriptor();
        f.setInt$(fd);
        return dup(f);
    }
    public static ParcelFileDescriptor[] createPipe() throws IOException {
        try {
            FileDescriptor[] fds = android.system.Os.pipe();
            return new ParcelFileDescriptor[] { new ParcelFileDescriptor(fds[0]), new ParcelFileDescriptor(fds[1]) };
        } catch (android.system.ErrnoException e) {
            throw new IOException(e);
        }
    }
    public static ParcelFileDescriptor adoptFd(int fd) {
        FileDescriptor f = new FileDescriptor();
        f.setInt$(fd);
        return new ParcelFileDescriptor(f);
    }
    public FileDescriptor getFileDescriptor() { return mFd; }
    public int getFd() { return mFd.getInt$(); }
    public int detachFd() { int fd = getFd(); mClosed = true; return fd; }
    public long seekTo(long pos) {
        try { return android.system.Os.lseek(mFd, pos, android.system.OsConstants.SEEK_SET); }
        catch (android.system.ErrnoException e) { return -1; }
    }
    public long getStatSize() {
        try { return Os.fstat(mFd).st_size; } catch (ErrnoException e) { return -1; }
    }
    @Override public void close() throws IOException {
        if (mClosed) return;
        mClosed = true;
        try { Os.close(mFd); } catch (ErrnoException e) { throw new IOException(e); }
    }
    public void closeWithError(String msg) throws IOException { close(); }
    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeFileDescriptor(mFd); }
    public static final Creator<ParcelFileDescriptor> CREATOR = new Creator<ParcelFileDescriptor>() {
        public ParcelFileDescriptor createFromParcel(Parcel in) { in.readInt(); return null; }
        public ParcelFileDescriptor[] newArray(int size) { return new ParcelFileDescriptor[size]; }
    };
    public static class AutoCloseOutputStream extends java.io.FileOutputStream {
        private final ParcelFileDescriptor mPfd;
        public AutoCloseOutputStream(ParcelFileDescriptor pfd) { super(pfd.getFileDescriptor()); mPfd = pfd; }
        @Override public void close() throws IOException { try { super.close(); } finally { mPfd.close(); } }
    }
    public static class AutoCloseInputStream extends FileInputStream {
        private final ParcelFileDescriptor mPfd;
        public AutoCloseInputStream(ParcelFileDescriptor pfd) { super(pfd.getFileDescriptor()); mPfd = pfd; }
        @Override public void close() throws IOException { try { super.close(); } finally { mPfd.close(); } }
    }
}
