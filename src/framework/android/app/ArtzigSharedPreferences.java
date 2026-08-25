/* artzig: simple persistent SharedPreferences (java-serialized map per name). */
package android.app;
import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public final class ArtzigSharedPreferences implements SharedPreferences {
    private static final Map<String, ArtzigSharedPreferences> sCache = new ConcurrentHashMap<>();
    private final File mFile;
    private final HashMap<String, Object> mMap = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> mListeners = new ArrayList<>();

    public static synchronized ArtzigSharedPreferences get(File dir, String name) {
        return sCache.computeIfAbsent(name, n -> new ArtzigSharedPreferences(new File(dir, n + ".bin")));
    }
    private ArtzigSharedPreferences(File file) {
        mFile = file;
        if (file.exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
                mMap.putAll((HashMap<String, Object>) in.readObject());
            } catch (Exception ignored) {}
        }
    }
    private synchronized void persist() {
        try {
            mFile.getParentFile().mkdirs();
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(mFile))) {
                out.writeObject(mMap);
            }
        } catch (IOException ignored) {}
    }
    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(mMap); }
    @Override public synchronized String getString(String key, String defValue) {
        Object v = mMap.get(key); return v instanceof String ? (String) v : defValue;
    }
    @Override public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = mMap.get(key); return v instanceof Set ? (Set<String>) v : defValues;
    }
    @Override public synchronized int getInt(String key, int defValue) {
        Object v = mMap.get(key); return v instanceof Integer ? (Integer) v : defValue;
    }
    @Override public synchronized long getLong(String key, long defValue) {
        Object v = mMap.get(key); return v instanceof Long ? (Long) v : defValue;
    }
    @Override public synchronized float getFloat(String key, float defValue) {
        Object v = mMap.get(key); return v instanceof Float ? (Float) v : defValue;
    }
    @Override public synchronized boolean getBoolean(String key, boolean defValue) {
        Object v = mMap.get(key); return v instanceof Boolean ? (Boolean) v : defValue;
    }
    @Override public synchronized boolean contains(String key) { return mMap.containsKey(key); }
    @Override public Editor edit() { return new EditorImpl(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        synchronized (mListeners) { mListeners.add(l); }
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        synchronized (mListeners) { mListeners.remove(l); }
    }
    private void notifyChanged(List<String> keys) {
        List<OnSharedPreferenceChangeListener> ls;
        synchronized (mListeners) { ls = new ArrayList<>(mListeners); }
        for (OnSharedPreferenceChangeListener l : ls) {
            for (String k : keys) l.onSharedPreferenceChanged(this, k);
        }
    }
    private final class EditorImpl implements Editor {
        private final HashMap<String, Object> mPending = new HashMap<>();
        private final HashSet<String> mRemovals = new HashSet<>();
        private boolean mClear;
        @Override public Editor putString(String key, String value) { mPending.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) {
            mPending.put(key, values != null ? new HashSet<>(values) : null);
            return this;
        }
        @Override public Editor putInt(String key, int value) { mPending.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { mPending.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { mPending.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { mPending.put(key, value); return this; }
        @Override public Editor remove(String key) { mRemovals.add(key); return this; }
        @Override public Editor clear() { mClear = true; return this; }
        @Override public boolean commit() { applyLocked(); return true; }
        @Override public void apply() { applyLocked(); }
        private void applyLocked() {
            List<String> changed = new ArrayList<>();
            synchronized (ArtzigSharedPreferences.this) {
                if (mClear) { changed.addAll(mMap.keySet()); mMap.clear(); }
                for (String k : mRemovals) { if (mMap.remove(k) != null) changed.add(k); }
                for (Map.Entry<String, Object> e : mPending.entrySet()) {
                    if (e.getValue() == null) mMap.remove(e.getKey());
                    else mMap.put(e.getKey(), e.getValue());
                    changed.add(e.getKey());
                }
                persist();
            }
            notifyChanged(changed);
        }
    }
}
