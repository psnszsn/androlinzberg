package dev.artzig.system;

import android.database.IContentObserver;
import android.net.Uri;

/**
 * The session's content-observer registry (the subset of
 * android.content.IContentService this host needs; the real interface is
 * dominated by the sync surface).
 */
interface IHostedContentService {
    void registerContentObserver(in Uri uri, boolean notifyForDescendants, IContentObserver observer);
    void unregisterContentObserver(IContentObserver observer);
    oneway void notifyChange(in Uri uri, IContentObserver observer, int flags);
}
