package dev.artzig.testing

import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.ResourcesImpl

import android.util.DisplayMetrics
import android.view.DisplayAdjustments

/**
 * Builds and updates isolated resource sets for resource smoke tests.
 *
 * This follows ResourcesManager.createResources/createResourcesImpl by pairing a
 * Resources instance with a ResourcesImpl. Production application resources use
 * the stock ResourcesManager and display services; fixtures supply explicit assets.
 */
public object HostedResources {
    @JvmStatic
    public fun create(
        assets: AssetManager,
        metrics: DisplayMetrics,
        configuration: Configuration,
        classLoader: ClassLoader? = null,
    ): Resources {
        val resources: Resources = Resources(classLoader)
        resources.setImpl(
            ResourcesImpl(
                assets,
                metrics,
                configuration,
                DisplayAdjustments(),
            ),
        )
        return resources
    }

    @JvmStatic
    public fun update(
        resources: Resources,
        configuration: Configuration,
        metrics: DisplayMetrics,
    ) {
        resources.updateConfiguration(configuration, metrics, null)
    }
}
