package dev.artzig.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.PermissionChecker
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import java.util.Objects

/** Pinned MediaRouterService / MediaRouter2ServiceImpl and SystemMediaRoute2Provider:
 * client registration, discovery/listing notifications, per-user suggestions and the persistent
 * system session. The existing desktop output is the only provider and has fixed volume;
 * remote-provider discovery and a system output-picker UI are absent on this host. */
internal class HostedMediaRouterService(
    private val context: Context,
    private val packages: HostedPackageManagerService,
) : IMediaRouterService.Stub() {
    private val lock = Any()
    private val handler = Handler.createAsync(Looper.getMainLooper())
    private val routers = HashMap<IBinder, Router>()
    private val managers = HashMap<IBinder, Manager>()
    private val legacy = HashMap<IBinder, Client>()
    private val suggestions = HashMap<Pair<Int, String>, MutableMap<String, List<SuggestedDeviceInfo>?>>()

    private open inner class Client(val callback: IInterface, val pkg: String, val user: Int) : IBinder.DeathRecipient {
        val binder = callback.asBinder()
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        var alive = true
        override fun binderDied() = remove(this)
    }
    private inner class Router(val router: IMediaRouter2, pkg: String, user: Int) : Client(router, pkg, user) {
        var discovery = RouteDiscoveryPreference.EMPTY
        var listing: RouteListingPreference? = null
        var scanning = MediaRouter2.SCANNING_STATE_NOT_SCANNING
    }
    private inner class Manager(val manager: IMediaRouter2Manager, pkg: String, user: Int, val target: String?) : Client(manager, pkg, user) {
        var scanning = MediaRouter2.SCANNING_STATE_NOT_SCANNING
    }
    private fun <T : Any> nn(value: T?): T = Objects.requireNonNull(value)!!
    private fun nonEmpty(value: String?): String { require(!value.isNullOrEmpty()); return value }
    private fun packageName(value: String?): String {
        val name = value
        if (name == null || packages.getPackagesForUid(Binder.getCallingUid())?.contains(name) != true) {
            throw SecurityException("packageName must match the calling uid")
        }
        return name
    }
    private fun routingPermission(pkg: String, pid: Int, uid: Int): Boolean =
        com.android.media.flags.Flags.enablePrivilegedRoutingForMediaRoutingControl() &&
            PermissionChecker.checkPermissionForDataDelivery(context, Manifest.permission.MEDIA_ROUTING_CONTROL,
                pid, uid, pkg, null, "Checking media routing permission") == PermissionChecker.PERMISSION_GRANTED

    private fun privileged(pkg: String) {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (context.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid) == PackageManager.PERMISSION_GRANTED) return
        if (routingPermission(pkg, pid, uid)) return
        throw SecurityException("Must hold MEDIA_CONTENT_CONTROL or MEDIA_ROUTING_CONTROL permissions.")
    }
    private fun checkUser(user: UserHandle): Int {
        val id = user.identifier
        if (id != UserHandle.getUserId(Binder.getCallingUid())) {
            context.enforceCallingOrSelfPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL, "media router target user")
        }
        return id
    }
    private fun send(client: Client, action: () -> Unit) {
        handler.post {
            if (synchronized(lock) { client.alive }) {
                try { action() } catch (_: RemoteException) { remove(client) }
            }
        }
    }
    private fun remove(client: Client) {
        synchronized(lock) {
            if (!client.alive) return
            client.alive = false
            routers.remove(client.binder)
            managers.remove(client.binder)
            legacy.remove(client.binder)
            client.binder.unlinkToDeath(client, 0)
            if (client is Router) {
                managers.values.filter { it.user == client.user }.forEach { manager ->
                    send(manager) { manager.manager.notifyDiscoveryPreferenceChanged(client.pkg, null) }
                }
            }
        }
    }
    private fun <T : Client> add(map: MutableMap<IBinder, T>, client: T): Boolean {
        if (map.containsKey(client.binder)) return false
        try { client.binder.linkToDeath(client, 0) }
        catch (failure: RemoteException) { throw RuntimeException("Media router client died prematurely.", failure) }
        map[client.binder] = client
        return true
    }
    private fun router(value: IMediaRouter2?): Router? = routers[nn(value).asBinder()]
    private fun manager(value: IMediaRouter2Manager?): Manager? = managers[nn(value).asBinder()]
    private fun session(pkg: String? = ""): RoutingSessionInfo = HostedMediaRoutes.systemSession(pkg)
    private fun routes(): List<MediaRoute2Info> = arrayListOf(HostedMediaRoutes.builtInAudio)
    private fun scanning(value: Int) {
        require(value == MediaRouter2.SCANNING_STATE_NOT_SCANNING || value == MediaRouter2.SCANNING_STATE_WHILE_INTERACTIVE || value == MediaRouter2.SCANNING_STATE_SCANNING_FULL) { "Scanning state $value is not valid." }
    }

    private fun enforceFullScan(client: Client, state: Int) {
        if (state != MediaRouter2.SCANNING_STATE_SCANNING_FULL) return
        if (com.android.media.flags.Flags.enableFullScanWithMediaContentControl() &&
            context.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, client.pid, client.uid) == PackageManager.PERMISSION_GRANTED) return
        if (routingPermission(client.pkg, client.pid, client.uid)) return
        throw SecurityException("Screen off scan requires MEDIA_ROUTING_CONTROL")
    }

    override fun getSystemRoutes(callerPackageName: String?, isProxyRouter: Boolean): List<MediaRoute2Info> {
        packageName(callerPackageName)
        return routes()
    }
    override fun getSystemSessionInfo(): RoutingSessionInfo = session()
    override fun getSystemSessionInfoForPackage(callerPackageName: String?, targetPackageName: String?): RoutingSessionInfo = session(targetPackageName)
    override fun showMediaOutputSwitcherWithRouter2(packageName: String?): Boolean {
        packageName(packageName)
        return false // No StatusBarManagerInternal output-picker implementation.
    }
    override fun registerRouter2(router: IMediaRouter2?, packageName: String?) {
        val record = Router(nn(router), packageName(packageName), UserHandle.getUserId(Binder.getCallingUid()))
        synchronized(lock) {
            if (add(routers, record)) send(record) { record.router.notifyRouterRegistered(routes(), session()) }
        }
    }
    override fun unregisterRouter2(router: IMediaRouter2?) { synchronized(lock) { router(router)?.let(::remove) } }
    override fun updateScanningStateWithRouter2(router: IMediaRouter2?, scanningState: Int) {
        nn(router); scanning(scanningState)
        synchronized(lock) { router(router)?.let { enforceFullScan(it, scanningState); it.scanning = scanningState } }
    }
    override fun setDiscoveryRequestWithRouter2(router: IMediaRouter2?, preference: RouteDiscoveryPreference?) {
        nn(preference)
        synchronized(lock) {
            val record = router(router) ?: return
            if (record.discovery == preference) return
            record.discovery = preference!!
            managers.values.filter { it.user == record.user }.forEach { target ->
                send(target) { target.manager.notifyDiscoveryPreferenceChanged(record.pkg, preference) }
            }
        }
    }
    override fun setRouteListingPreference(router: IMediaRouter2?, routeListingPreference: RouteListingPreference?) {
        routeListingPreference?.linkedItemComponentName?.let { component ->
            // Pinned MediaServerUtils: root/shell are trusted; other callers must own the package.
            val uid = Binder.getCallingUid()
            if (uid != Process.ROOT_UID && uid != Process.SHELL_UID) {
                require(packages.getPackagesForUid(uid)?.contains(component.packageName) == true) {
                    "packageName does not belong to the calling uid"
                }
            }
            val intent = Intent(RouteListingPreference.ACTION_TRANSFER_MEDIA).setComponent(component)
            require(context.packageManager.queryIntentActivitiesAsUser(intent, 0, Binder.getCallingUserHandle()).isNotEmpty()) {
                "Unable to resolve $component to a valid activity for ${RouteListingPreference.ACTION_TRANSFER_MEDIA}"
            }
        }
        synchronized(lock) {
            val record = router(router) ?: return
            record.listing = routeListingPreference
            managers.values.filter { it.user == record.user }.forEach { target ->
                send(target) { target.manager.notifyRouteListingPreferenceChange(record.pkg, routeListingPreference) }
            }
        }
    }
    override fun setRouteVolumeWithRouter2(router: IMediaRouter2?, route: MediaRoute2Info?, volume: Int) { nn(router); nn(route) }
    override fun requestCreateSessionWithRouter2(router: IMediaRouter2?, requestId: Int, managerRequestId: Long,
        oldSession: RoutingSessionInfo?, route: MediaRoute2Info?, sessionHints: Bundle?) {
        nn(oldSession); nn(route)
        synchronized(lock) {
            val record = router(router) ?: return
            val result = if (route!!.id == HostedMediaRoutes.builtInAudio.id) session(record.pkg) else null
            send(record) { record.router.notifySessionCreated(requestId, result) }
        }
    }
    // AOSP's system provider does not group routes, change session volume, or release its session.
    override fun selectRouteWithRouter2(router: IMediaRouter2?, sessionId: String?, route: MediaRoute2Info?) { nn(router); nonEmpty(sessionId); nn(route) }
    override fun deselectRouteWithRouter2(router: IMediaRouter2?, sessionId: String?, route: MediaRoute2Info?) { nn(router); nonEmpty(sessionId); nn(route) }
    override fun transferToRouteWithRouter2(router: IMediaRouter2?, sessionId: String?, route: MediaRoute2Info?) { nn(router); nonEmpty(sessionId); nn(route) }
    override fun setSessionVolumeWithRouter2(router: IMediaRouter2?, sessionId: String?, volume: Int) { nn(router); nonEmpty(sessionId) }
    override fun releaseSessionWithRouter2(router: IMediaRouter2?, sessionId: String?) { nn(router); nonEmpty(sessionId) }

    override fun registerManager(manager: IMediaRouter2Manager?, packageName: String?) {
        registerManager(nn(manager), nonEmpty(packageName), null, UserHandle.getUserId(Binder.getCallingUid()))
    }
    override fun registerProxyRouter(manager: IMediaRouter2Manager?, callingPackageName: String?, targetPackageName: String?, targetUser: UserHandle?) {
        nn(manager); nn(targetUser)
        val caller = nonEmpty(callingPackageName)
        val target = nonEmpty(targetPackageName)
        privileged(caller)
        val user = checkUser(targetUser!!)
        require(packages.getPackageUid(target, 0, user) >= 0) { "targetPackageName does not exist: $target" }
        registerManager(manager!!, caller, target, user)
    }
    private fun registerManager(callback: IMediaRouter2Manager, pkg: String, target: String?, user: Int) {
        privileged(pkg)
        synchronized(lock) {
            val record = Manager(callback, pkg, user, target)
            if (!add(managers, record)) return
            routers.values.filter { it.user == user }.forEach { router ->
                val discovery = router.discovery
                val listing = router.listing
                send(record) { callback.notifyRouteListingPreferenceChange(router.pkg, listing) }
                send(record) { callback.notifyDiscoveryPreferenceChanged(router.pkg, discovery) }
            }
            send(record) { callback.notifyRoutesUpdated(routes()) }
        }
    }
    override fun unregisterManager(manager: IMediaRouter2Manager?) { synchronized(lock) { manager(manager)?.let(::remove) } }
    override fun getRemoteSessions(manager: IMediaRouter2Manager?): List<RoutingSessionInfo> { nn(manager); return ArrayList() }
    override fun updateScanningState(manager: IMediaRouter2Manager?, scanningState: Int) {
        nn(manager); scanning(scanningState)
        synchronized(lock) { manager(manager)?.let { enforceFullScan(it, scanningState); it.scanning = scanningState } }
    }
    private fun managerOperation(manager: IMediaRouter2Manager?, requestId: Int, sessionId: String?, route: MediaRoute2Info?, needsRoute: Boolean) {
        nonEmpty(sessionId)
        if (needsRoute) nn(route)
        synchronized(lock) {
            val record = manager(manager) ?: return
            val reason = when {
                sessionId != session().id -> MediaRoute2ProviderService.REASON_INVALID_COMMAND
                route != null && route.id != HostedMediaRoutes.builtInAudio.id -> MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE
                else -> return // Persistent, fixed-volume system route; no grouping or release.
            }
            send(record) { record.manager.notifyRequestFailed(requestId, reason) }
        }
    }
    override fun setRouteVolumeWithManager(manager: IMediaRouter2Manager?, requestId: Int, route: MediaRoute2Info?, volume: Int) = managerOperation(manager, requestId, session().id, route, true)
    override fun requestCreateSessionWithManager(manager: IMediaRouter2Manager?, requestId: Int, oldSession: RoutingSessionInfo?, route: MediaRoute2Info?) {
        nn(oldSession); nn(route)
        synchronized(lock) {
            val record = manager(manager) ?: return
            // A stock manager asks the owning router to create remote sessions. None exist here;
            // SystemMediaRoute2Provider can create only the already-selected system session.
            if (route!!.id == HostedMediaRoutes.builtInAudio.id) {
                send(record) { record.manager.notifySessionCreated(requestId, session(oldSession!!.clientPackageName)) }
            } else send(record) { record.manager.notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE) }
        }
    }
    override fun selectRouteWithManager(manager: IMediaRouter2Manager?, requestId: Int, sessionId: String?, route: MediaRoute2Info?) = managerOperation(manager, requestId, sessionId, route, true)
    override fun deselectRouteWithManager(manager: IMediaRouter2Manager?, requestId: Int, sessionId: String?, route: MediaRoute2Info?) = managerOperation(manager, requestId, sessionId, route, true)
    override fun transferToRouteWithManager(manager: IMediaRouter2Manager?, requestId: Int, sessionId: String?, route: MediaRoute2Info?, transferInitiatorUserHandle: UserHandle?, transferInitiatorPackageName: String?) {
        nn(transferInitiatorUserHandle); nn(transferInitiatorPackageName)
        managerOperation(manager, requestId, sessionId, route, true)
    }
    override fun setSessionVolumeWithManager(manager: IMediaRouter2Manager?, requestId: Int, sessionId: String?, volume: Int) = managerOperation(manager, requestId, sessionId, null, false)
    override fun releaseSessionWithManager(manager: IMediaRouter2Manager?, requestId: Int, sessionId: String?) = managerOperation(manager, requestId, sessionId, null, false)
    override fun showMediaOutputSwitcherWithProxyRouter(manager: IMediaRouter2Manager?): Boolean { nn(manager); return false }

    private fun updateSuggestions(user: Int, target: String, from: String, values: List<SuggestedDeviceInfo>?) {
        val copy = values?.toList()
        suggestions.getOrPut(user to target) { HashMap() }[from] = copy
        routers.values.filter { it.user == user && it.pkg == target }.forEach { record ->
            send(record) { record.router.notifyDeviceSuggestionsUpdated(from, copy) }
        }
        managers.values.filter { it.user == user }.forEach { record ->
            send(record) { record.manager.notifyDeviceSuggestionsUpdated(target, from, copy) }
        }
    }
    private fun suggestions(user: Int, pkg: String): Map<String, List<SuggestedDeviceInfo>?>? =
        suggestions[user to pkg]?.mapValues { it.value?.toList() }
    override fun setDeviceSuggestionsWithRouter2(router: IMediaRouter2?, suggestedDeviceInfo: List<SuggestedDeviceInfo>?) {
        synchronized(lock) { val record = router(router) ?: return; updateSuggestions(record.user, record.pkg, record.pkg, suggestedDeviceInfo) }
    }
    override fun getDeviceSuggestionsWithRouter2(router: IMediaRouter2?): Map<String, List<SuggestedDeviceInfo>?>? =
        synchronized(lock) { router(router)?.let { suggestions(it.user, it.pkg) } }
    override fun setDeviceSuggestionsWithManager(manager: IMediaRouter2Manager?, suggestedDeviceInfo: List<SuggestedDeviceInfo>?) {
        synchronized(lock) { val record = manager(manager) ?: return; updateSuggestions(record.user, record.target ?: return, record.pkg, suggestedDeviceInfo) }
    }
    override fun getDeviceSuggestionsWithManager(manager: IMediaRouter2Manager?): Map<String, List<SuggestedDeviceInfo>?>? =
        synchronized(lock) { manager(manager)?.let { suggestions(it.user, it.target ?: return null) } }

    // Legacy MediaRouter owns its local routes. The service reports only remote global routes,
    // of which there are none without an Android MediaRouteProvider service.
    override fun registerClientAsUser(client: IMediaRouterClient?, packageName: String?, userId: Int) {
        require(client != null) { "client must not be null" }
        val pkg = packageName(packageName)
        checkUser(UserHandle.of(userId))
        synchronized(lock) { add(legacy, Client(client, pkg, userId)) }
    }
    override fun unregisterClient(client: IMediaRouterClient?) { require(client != null); synchronized(lock) { legacy[client.asBinder()]?.let(::remove) } }
    override fun registerClientGroupId(client: IMediaRouterClient?, groupId: String?) { require(client != null) }
    override fun getState(client: IMediaRouterClient?): MediaRouterClientState? = synchronized(lock) {
        if (legacy.containsKey(nn(client).asBinder())) MediaRouterClientState() else null
    }
    override fun isPlaybackActive(client: IMediaRouterClient?): Boolean { nn(client); return false } // No AudioPlayerStateMonitor records yet.
    override fun setBluetoothA2dpOn(client: IMediaRouterClient?, on: Boolean) { require(client != null) } // No Bluetooth audio device.
    override fun setDiscoveryRequest(client: IMediaRouterClient?, routeTypes: Int, activeScan: Boolean) { require(client != null) }
    override fun setSelectedRoute(client: IMediaRouterClient?, routeId: String?, explicit: Boolean) { require(client != null) }
    override fun requestSetVolume(client: IMediaRouterClient?, routeId: String?, volume: Int) { nn(client); nn(routeId) }
    override fun requestUpdateVolume(client: IMediaRouterClient?, routeId: String?, direction: Int) { nn(client); nn(routeId) }
}
