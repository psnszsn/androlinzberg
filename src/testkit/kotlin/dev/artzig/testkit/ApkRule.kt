package dev.artzig.testkit

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class ApkRule(private val apkKey: String) : TestRule {
    private lateinit var session: AppSession

    /** Keeps expression-bodied JUnit tests void even when the last action returns a value. */
    fun run(block: AppSession.() -> Unit) {
        session.block()
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            session = AppSession.start(apkKey, "${description.className}.${description.methodName}")
            try {
                base.evaluate()
                session.finishSuccessfully()
            } catch (error: Throwable) {
                session.captureFailure(error)
                throw error
            } finally {
                session.close()
            }
        }
    }

    fun waitFor(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        absent: Boolean = false,
    ) = session.waitFor(resourceId, text, description, className, role, absent)

    fun expectVisible(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
    ) = session.expectVisible(resourceId, text, description, className, role)

    fun click(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        via: InputVia = InputVia.WAYLAND,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.click(resourceId, text, description, className, role, via, settle)

    fun setText(
        value: String,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.setText(value, resourceId, text, description, className, role, settle)

    fun type(
        value: String,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.type(value, resourceId, text, description, className, role, settle)

    fun key(
        value: String,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.key(value, resourceId, text, description, className, role, settle)

    fun assertLog(tag: String, containing: String) = session.assertLog(tag, containing)

    fun expectIssue(
        kind: String,
        owner: String,
        member: String,
        block: AppSession.() -> StepResult,
    ) = session.expectIssue(kind, owner, member, block)
}
