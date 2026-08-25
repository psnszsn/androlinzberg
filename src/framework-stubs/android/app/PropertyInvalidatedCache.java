/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class PropertyInvalidatedCache<Query, Result> {
    public PropertyInvalidatedCache() {}
    public PropertyInvalidatedCache(Args args, String api, QueryHandler<Query, Result> handler) {}
    public static final class Args {
        public Args(int module) {}
        public Args maxEntries(int max) { return this; }
        public Args api(String api) { return this; }
        public Args cacheNulls(boolean enable) { return this; }
        public Args isolateUids(boolean enable) { return this; }
    }
    public abstract static class QueryHandler<Q, R> {
        public abstract R apply(Q query);
        public boolean shouldBypassCache(Q query) { return false; }
    }
    public PropertyInvalidatedCache(int maxEntries, String propertyName) {}
    public PropertyInvalidatedCache(int maxEntries, int module, String api, String cacheName) {}
    protected Result recompute(Query query) { return null; }
    public Result query(Query query) { return recompute(query); }
    public void disableLocal() {}
    public void invalidateCache() {}
    public static void invalidateCache(String api) {}
    public static void invalidateCache(int module, String api) {}
    public static final int MODULE_SYSTEM = 1, MODULE_BLUETOOTH = 2, MODULE_TELEPHONY = 3;
    public static String createSystemCacheKey(String api) { return api; }
    public static String createPropertyName(int module, String api) { return api; }
}
