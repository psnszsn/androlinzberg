package android.content.res

import android.util.DisplayMetrics
import android.view.DisplayAdjustments

/**
 * Builds and updates resources for the single-process host.
 *
 * This follows ResourcesManager.createResources/createResourcesImpl by pairing a
 * Resources instance with a ResourcesImpl. The full ResourcesManager flow cannot
 * be reused because it depends on Android process and display infrastructure that
 * is absent from the host.
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
