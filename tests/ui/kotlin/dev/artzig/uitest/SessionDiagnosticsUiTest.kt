package dev.artzig.uitest

import android.os.ServiceManager
import android.safetycenter.ISafetyCenterManager
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The issue journal every UI test is checked against really records gaps. */
class SessionDiagnosticsUiTest {
    @Test
    fun unsupportedFrameworkCallIsJournalled() {
        UiTest.allowRuntimeIssues()
        val before = UiTest.diagnostics.read(Long.MAX_VALUE, Long.MAX_VALUE)
        // The hosted SafetyCenter service implements isSafetyCenterEnabled only.
        val safetyCenter = ISafetyCenterManager.Stub.asInterface(ServiceManager.getService("safety_center"))
        try {
            safetyCenter.safetyCenterConfig
            fail("the hosted SafetyCenter service has no configuration")
        } catch (expected: UnsupportedOperationException) {
        }
        val issues = UiTest.diagnostics.read(before.logCursor, before.issueCursor).issues
        assertTrue("journalled: ${issues.map { "${it.kind} ${it.owner}.${it.member}" }}",
            issues.any { it.kind == "unimplemented" && it.owner == "ISafetyCenterManager" && it.member == "getSafetyCenterConfig" })
    }
}
