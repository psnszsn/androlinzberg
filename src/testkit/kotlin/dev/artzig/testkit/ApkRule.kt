package dev.artzig.testkit

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class ApkRule(
    private val apkKey: String,
    private val launchSize: LaunchSize? = null,
) : TestRule {
    private lateinit var session: AppSession

    /** Keeps expression-bodied JUnit tests void even when the last action returns a value. */
    fun run(block: AppSession.() -> Unit) {
        session.block()
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            session = AppSession.start(
                apkKey,
                "${description.className}.${description.methodName}",
                launchSize = launchSize,
            )
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
        index: Int? = null,
        absent: Boolean = false,
        visible: Boolean = true,
        stateDescription: String? = null,
    ) = session.waitFor(
        resourceId, text, description, className, role, index, absent, visible, stateDescription,
    )

    fun expectVisible(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
    ) = session.expectVisible(resourceId, text, description, className, role, index)

    fun isVisible(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
    ) = session.isVisible(resourceId, text, description, className, role)

    fun click(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
        via: InputVia = InputVia.WAYLAND,
        settle: WaitKind = WaitKind.IDLE,
        xFraction: Double? = null,
        yFraction: Double? = null,
    ) = session.click(resourceId, text, description, className, role, index, via, settle, xFraction, yFraction)

    fun drag(
        fromXFraction: Double,
        fromYFraction: Double,
        toXFraction: Double,
        toYFraction: Double,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.drag(
        fromXFraction, fromYFraction, toXFraction, toYFraction,
        resourceId, text, description, className, role, index, settle,
    )

    fun scrollForward(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.scrollForward(
        resourceId, text, description, className, role, index, settle,
    )

    fun scrollBackward(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
        settle: WaitKind = WaitKind.IDLE,
    ) = session.scrollBackward(
        resourceId, text, description, className, role, index, settle,
    )

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

    fun waitForFullscreen(expected: Boolean, timeoutMillis: Long = 5_000) =
        session.waitForFullscreen(expected, timeoutMillis)

    fun assertFullscreenRemains(expected: Boolean, durationMillis: Long = 1_500) =
        session.assertFullscreenRemains(expected, durationMillis)

    fun waitForPresentedFrames(count: Int) = session.waitForPresentedFrames(count)

    fun screenshot(fileName: String) = session.screenshot(fileName)

    fun expectIssue(
        kind: String,
        owner: String,
        member: String,
        block: AppSession.() -> StepResult,
    ) = session.expectIssue(kind, owner, member, block)
}
