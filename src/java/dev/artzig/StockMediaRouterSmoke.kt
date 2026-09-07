package dev.artzig

import android.app.ActivityThread
import android.content.ComponentName
import android.media.*
import android.os.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/** Public stock router and generated Binder surface, including callback scheduling and failures. */
internal object StockMediaRouterSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.simpleName}, got $failure" }
    }
    fun run(pump: () -> Unit) {
        val context = checkNotNull(ActivityThread.currentApplication())
        val router = MediaRouter2.getInstance(context)
        val service = IMediaRouterService.Stub.asInterface(ServiceManager.getService(android.content.Context.MEDIA_ROUTER_SERVICE))
        val controller = router.systemController
        check(!controller.isReleased)
        val route = controller.selectedRoutes.single()
        check(route.isSystemRoute && route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        check(controller.volumeHandling == MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
        check(controller.selectableRoutes.isEmpty() && controller.deselectableRoutes.isEmpty())
        check(controller.transferableRoutes.isEmpty())
        check(router.getController(controller.id) === controller)
        check(router.getController("missing") == null)
        controller.setVolume(0)
        controller.release()
        check(!controller.isReleased && controller.selectedRoutes.single() == route)

        val jobs = ConcurrentLinkedQueue<Runnable>()
        val executor = Executor { jobs.add(it) }
        val observed = ArrayList<List<MediaRoute2Info>>()
        val callback = object : MediaRouter2.RouteCallback() {
            override fun onRoutesUpdated(routes: List<MediaRoute2Info>) { observed.add(routes.toList()) }
        }
        fun settle() { repeat(4) { pump(); while (true) { val job = jobs.poll() ?: break; job.run() }; Thread.sleep(5) } }
        fun awaitState(description: String, ready: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do { settle(); if (ready()) return } while (System.nanoTime() < deadline)
            error("Media router did not reach $description")
        }
        val preference = RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build()
        router.registerRouteCallback(executor, callback, preference)
        check(observed.isEmpty()) { "Route callback bypassed its executor" }
        awaitState("initial route callback") { observed.any { it.contains(route) } }
        check(router.routes.single() == route)
        check(observed.any { it.contains(route) }) { "Stock route callback did not receive initial output" }
        // Registering the same callback updates discovery; it does not duplicate registration.
        router.registerRouteCallback(executor, callback, RouteDiscoveryPreference.EMPTY)
        awaitState("empty discovery") { router.routes.isEmpty() }
        check(router.routes.isEmpty()) { "Stock feature filtering ignored empty discovery" }
        router.registerRouteCallback(executor, callback, preference)
        awaitState("audio discovery") { router.routes == listOf(route) }
        check(router.routes.single() == route)

        check(route.providerPackageName == "com.android.server.media")
        router.registerRouteCallback(executor, callback,
            RouteDiscoveryPreference.Builder(preference).setAllowedPackages(listOf("another.provider")).build())
        awaitState("provider filtering") { router.routes.isEmpty() }
        router.registerRouteCallback(executor, callback,
            RouteDiscoveryPreference.Builder(preference).setAllowedPackages(listOf(route.providerPackageName)).build())
        awaitState("matching provider") { router.routes == listOf(route) }

        val suggestion = SuggestedDeviceInfo.Builder().setDeviceDisplayName("Desktop output")
            .setRouteId(route.id).setType(route.type).build()
        val updates = ArrayList<String>()
        val suggestionsCallback = object : MediaRouter2.DeviceSuggestionsCallback {
            override fun onSuggestionUpdated(packageName: String, suggestions: List<SuggestedDeviceInfo>?) { updates.add(packageName) }
        }
        router.registerDeviceSuggestionsCallback(executor, suggestionsCallback)
        router.setDeviceSuggestions(listOf(suggestion))
        awaitState("suggestion callback") { updates.contains(context.packageName) }
        check(checkNotNull(router.deviceSuggestions)[context.packageName] == listOf(suggestion))
        check(updates.contains(context.packageName))
        router.setDeviceSuggestions(emptyList())
        settle()
        check(checkNotNull(router.deviceSuggestions)[context.packageName]?.isEmpty() == true)
        router.unregisterDeviceSuggestionsCallback(suggestionsCallback)

        rejects<SecurityException> { service.getSystemRoutes("not.the.calling.package", false) }
        val deniedManager = object : IMediaRouter2Manager.Stub(),
            IMediaRouter2Manager by dev.artzig.system.unsupported<IMediaRouter2Manager>() {
            override fun asBinder(): IBinder = this
        }
        rejects<SecurityException> { service.registerManager(deniedManager, context.packageName) }
        rejects<SecurityException> { service.registerProxyRouter(deniedManager, context.packageName, context.packageName, Process.myUserHandle()) }
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val raw = object : IMediaRouter2.Stub() {
            override fun notifyRouterRegistered(routes: List<MediaRoute2Info>?, session: RoutingSessionInfo?) { events.add("registered") }
            override fun notifyRoutesUpdated(routes: List<MediaRoute2Info>?) {}
            override fun notifySessionCreated(requestId: Int, session: RoutingSessionInfo?) { events.add("created:$requestId:${session != null}") }
            override fun notifySessionInfoChanged(session: RoutingSessionInfo?) {}
            override fun notifySessionReleased(session: RoutingSessionInfo?) { events.add("released") }
            override fun requestCreateSessionByManager(requestId: Long, oldSession: RoutingSessionInfo?, route: MediaRoute2Info?) {}
            override fun notifyDeviceSuggestionsUpdated(pkg: String?, suggestions: List<SuggestedDeviceInfo>?) {}
        }
        service.registerRouter2(raw, context.packageName)
        service.registerRouter2(raw, context.packageName)
        awaitState("router registration") { events.isNotEmpty() }
        check(events == listOf("registered"))
        rejects<IllegalArgumentException> { service.updateScanningStateWithRouter2(raw, -1) }
        rejects<SecurityException> { service.updateScanningStateWithRouter2(raw, MediaRouter2.SCANNING_STATE_SCANNING_FULL) }
        service.updateScanningStateWithRouter2(raw, MediaRouter2.SCANNING_STATE_WHILE_INTERACTIVE)
        val invalidListing = RouteListingPreference.Builder()
            .setLinkedItemComponentName(ComponentName(context.packageName, "MissingRouteActivity")).build()
        rejects<IllegalArgumentException> { service.setRouteListingPreference(raw, invalidListing) }
        val session = service.systemSessionInfo
        service.requestCreateSessionWithRouter2(raw, 17, 0, session, route, null)
        val absent = MediaRoute2Info.Builder("absent", "Missing").addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO).build()
        service.requestCreateSessionWithRouter2(raw, 18, 0, session, absent, null)
        service.releaseSessionWithRouter2(raw, session.id)
        awaitState("session results") { events.size == 3 }
        check(events == listOf("registered", "created:17:true", "created:18:false")) { events.toString() }
        service.unregisterRouter2(raw)
        service.requestCreateSessionWithRouter2(raw, 19, 0, session, route, null)
        settle()
        check(events.size == 3)
        router.unregisterRouteCallback(callback)
        router.unregisterRouteCallback(callback) // Pinned API ignores an unknown registration.
        settle()
        check(router.systemController === controller && !controller.isReleased)
        val legacy = checkNotNull(context.getSystemService(MediaRouter::class.java))
        val defaultRoute = legacy.defaultRoute
        check(defaultRoute.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
        check(defaultRoute.volume == 10 && defaultRoute.volumeMax == 15)
        defaultRoute.requestSetVolume(0)
        defaultRoute.requestUpdateVolume(-1)
        check(defaultRoute.volume == 10)
        check(legacy.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) === defaultRoute)
        println("stock media router clients: PASS")
    }
}
