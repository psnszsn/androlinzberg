/* artzig stub: legacy platform fragments (apps use androidx; referenced for type only). */
package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class Fragment implements android.content.ComponentCallbacks2 {
    public Fragment() {}
    public Activity getActivity() { return null; }
    public Context getContext() { return null; }
    public Resources getResources() { return null; }
    public Bundle getArguments() { return null; }
    public void setArguments(Bundle args) {}
    public View getView() { return null; }
    public boolean isAdded() { return false; }
    public boolean isDetached() { return false; }
    public boolean isRemoving() { return false; }
    public boolean isResumed() { return false; }
    public boolean isVisible() { return false; }
    public String getTag() { return null; }
    public int getId() { return 0; }
    public void startActivity(Intent intent) {}
    public void onCreate(Bundle savedInstanceState) {}
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) { return null; }
    public void onDestroy() {}
    public void onPause() {}
    public void onResume() {}
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {}
    public void onLowMemory() {}
    public void onTrimMemory(int level) {}
}
