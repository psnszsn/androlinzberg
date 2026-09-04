package dev.artzig.system

import android.os.IBinder
import android.os.IIncidentAuthListener
import android.os.IncidentManager
import android.os.IncidentReportArgs

/** Incident services for a desktop session, where incidentd and approval UI are absent. */
class HostedIncidentManagerService :
    android.os.IIncidentManager.Stub(),
    android.os.IIncidentManager by unsupported<android.os.IIncidentManager>() {
    override fun asBinder(): IBinder = this

    // A report cannot be persisted without incidentd, matching the stock client's
    // successful no-service behavior.
    override fun reportIncident(args: IncidentReportArgs?) = Unit
    override fun getIncidentReportList(pkg: String?, cls: String?): MutableList<String> =
        mutableListOf()
    override fun getIncidentReport(pkg: String?, cls: String?, id: String?): IncidentManager.IncidentReport? =
        null
    override fun deleteIncidentReports(pkg: String?, cls: String?, id: String?) = Unit
    override fun deleteAllIncidentReports(pkg: String?) = Unit
}

class HostedIncidentCompanionService :
    android.os.IIncidentCompanion.Stub(),
    android.os.IIncidentCompanion by unsupported<android.os.IIncidentCompanion>() {
    override fun asBinder(): IBinder = this

    // There is no approval surface on the host, so authorization terminates as
    // denied instead of leaving a callback pending forever.
    override fun authorizeReport(
        callingUid: Int,
        callingPackage: String?,
        receiverClass: String?,
        reportId: String?,
        flags: Int,
        callback: IIncidentAuthListener?,
    ) {
        callback?.onReportDenied()
    }

    override fun cancelAuthorization(callback: IIncidentAuthListener?) = Unit
    override fun getPendingReports(): MutableList<String> = mutableListOf()
    override fun approveReport(uri: String?) = Unit
    override fun denyReport(uri: String?) = Unit
    override fun getIncidentReportList(pkg: String?, cls: String?): MutableList<String> =
        mutableListOf()
    override fun getIncidentReport(pkg: String?, cls: String?, id: String?): IncidentManager.IncidentReport? =
        null
    override fun deleteIncidentReports(pkg: String?, cls: String?, id: String?) = Unit
    override fun deleteAllIncidentReports(pkg: String?) = Unit
}
