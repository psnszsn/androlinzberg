/* artzig stub: appwidget adapter — unused on host. */
package android.widget;
public class RemoteViewsAdapter extends BaseAdapter {
    public RemoteViewsAdapter() {}
    public RemoteViewsAdapter(android.content.Context context, android.content.Intent intent,
            RemoteAdapterConnectionCallback callback, boolean useAsyncLoader) {}
    public static class AsyncRemoteAdapterAction implements Runnable {
        public AsyncRemoteAdapterAction(RemoteAdapterConnectionCallback callback, android.content.Intent intent) {}
        @Override public void run() {}
    }
    public interface RemoteAdapterConnectionCallback {
        boolean onRemoteAdapterConnected();
        void onRemoteAdapterDisconnected();
        void deferNotifyDataSetChanged();
        void setRemoteViewsAdapter(android.content.Intent intent, boolean isAsync);
    }
    public interface RemoteViewsFactoryProvider {}
    @Override public int getCount() { return 0; }
    @Override public Object getItem(int position) { return null; }
    @Override public long getItemId(int position) { return 0; }
    @Override public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) { return null; }
    public void saveRemoteViewsCache() {}
    public boolean isDataReady() { return false; }
    public void notifyDataSetChanged() {}
    public android.content.Intent getRemoteViewsServiceIntent() { return null; }
    public void superNotifyDataSetChanged() {}
    public void setVisibleRangeHint(int lowerBound, int upperBound) {}
    public void setRemoteViewsInteractionHandler(RemoteViews.InteractionHandler handler) {}
}
