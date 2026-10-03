package dev.artzig.settings

import android.app.Activity
import android.app.AppOpsManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Settings' ExternalSourcesDetails for one source package: "Allow from this
 * source" sets OP_REQUEST_INSTALL_PACKAGES to MODE_ALLOWED or MODE_ERRORED, the
 * values PackageInstaller's unknown-sources check and app-op listener observe.
 */
class ManageUnknownAppSourcesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.data?.schemeSpecificPart
        if (packageName == null) {
            finish()
            return
        }
        val info = packageManager.getApplicationInfo(packageName, 0)
        val appOps = getSystemService(AppOpsManager::class.java)
        val allowed = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES, info.uid, packageName) ==
            AppOpsManager.MODE_ALLOWED
        val toggle = Switch(this).apply {
            text = "Allow from this source"
            isChecked = allowed
            setOnCheckedChangeListener { _, checked ->
                appOps.setMode(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, info.uid, packageName,
                    if (checked) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED)
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@ManageUnknownAppSourcesActivity).apply { text = packageManager.getApplicationLabel(info) })
            addView(toggle)
        })
    }
}
