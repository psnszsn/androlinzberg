@file:Suppress("DEPRECATION")
package dev.artzig.window

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import com.android.internal.infra.AndroidFuture
import com.android.internal.inputmethod.IInputMethodSession
import com.android.internal.inputmethod.IRemoteInputConnection
import com.android.internal.inputmethod.InputConnectionCommandHeader

/**
 * Source note: follows InputMethodManager's IInputMethodSession binding and
 * RemoteInputConnectionImpl's session-header, batch and query ordering. An
 * Android InputMethodService process is absent: the compositor supplies that
 * editor interaction through text-input-v3. All state runs on the shell handler;
 * remote queries complete asynchronously, with bounded queues and deadlines.
 */
internal class WaylandInputMethod(private val handler: Handler, private val host: Host) {
    interface Host {
        fun task(window: IBinder, uid: Int, pid: Int): TaskGeometry.Task?
        fun publish(task: TaskGeometry.Task, generation: Long, applied: Long, enabled: Boolean,
            context: WaylandTextContext.Wire?, hint: Int, purpose: Int, external: Boolean, rectangle: IntArray): Boolean
        fun cursor(task: TaskGeometry.Task, points: FloatArray): IntArray
    }
    data class Edit(val generation: Long, val sequence: Long, val fields: Int,
        val preedit: String, val cursorBegin: Int, val cursorEnd: Int, val committed: String,
        val before: Int, val after: Int)
    @Volatile var available = false
        private set
    private var focus: TaskGeometry.Task? = null
    private var active: Session? = null
    private var nextGeneration = 0L
    private fun owner() = check(Looper.myLooper() === handler.looper)
    fun availability(value: Boolean) {
        owner(); available = value
        if (!value) { focus = null; active?.let { it.withConnection { it.suspendEditor() } } }
    }
    fun focus(task: TaskGeometry.Task, gained: Boolean) {
        owner()
        if (gained) {
            if (focus != task) active?.let { it.withConnection { it.suspendEditor() } }
            focus = task
            active?.let { it.withConnection { it.activate() } }
        } else if (focus == task) {
            focus = null
            active?.let { it.withConnection { it.suspendEditor() } }
        }
    }
    fun removed(task: TaskGeometry.Task) {
        owner()
        if (focus == task) focus = null
        if (active?.task == task) { active?.retire(); active = null }
    }
    fun edit(task: TaskGeometry.Task, value: Edit) {
        owner()
        active?.takeIf { it.task == task && focus == task }?.let { it.withConnection { it.enqueue(value) } }
    }

    /** Called only after IMMS verifies client, user, focused window and package. */
    fun start(window: IBinder, uid: Int, pid: Int, editor: EditorInfo,
        connection: IRemoteInputConnection): IInputMethodSession? {
        if (!available || editor.inputType == InputType.TYPE_NULL) return null
        val session = Session(window, uid, pid, editor.createCopyInternal(), connection)
        check(handler.post {
            active?.retire()
            active = session
            session.withConnection { session.activate() }
        })
        return session
    }
    fun finish(window: IBinder?) {
        check(handler.post {
            if (window == null || active?.window == window) { active?.retire(); active = null }
        })
    }

    private inner class Session(val window: IBinder, val uid: Int, val pid: Int,
        var editor: EditorInfo, var connection: IRemoteInputConnection) : IInputMethodSession.Stub() {
        var task: TaskGeometry.Task? = null
        private var generation = 0L
        private var header = InputConnectionCommandHeader(0)
        private var retired = false
        private var enabled = false
        private var applying = false
        private var refreshPending = false
        private var query: AndroidFuture<SurroundingText>? = null
        private var cursorQuery: AndroidFuture<Boolean>? = null
        private val edits = java.util.ArrayDeque<Edit>()
        private var accepted = 0L
        private var applied = 0L
        private var composingStart = -1
        private var composingEnd = -1
        private var ownPreedit = false
        private var hiddenCaret = false
        private var rectangle = intArrayOf()
        private val queued = java.util.concurrent.atomic.AtomicInteger()

        private fun dispatch(action: () -> Unit) {
            val caller = Binder.getCallingUid()
            if (caller != android.os.Process.SYSTEM_UID && (caller != uid || Binder.getCallingPid() != pid))
                throw SecurityException("Input session belongs to uid=$uid/pid=$pid")
            if (queued.incrementAndGet() > 128) {
                queued.decrementAndGet()
                throw IllegalStateException("Input session callback queue exhausted")
            }
            if (!handler.post { queued.decrementAndGet(); if (!retired && active === this) withConnection(action) }) {
                queued.decrementAndGet(); throw IllegalStateException("Input session handler stopped")
            }
        }
        private fun live() = !retired && active === this && enabled && task == focus
        fun withConnection(action: () -> Unit) {
            try { action() }
            catch (failure: android.os.RemoteException) {
                // A dying editor must not terminate other tasks in system_server.
                retire()
                android.util.Log.i("WaylandIME", "Retired disconnected editor uid=$uid/pid=$pid")
            }
        }
        private fun sensitive(): Boolean {
            val kind = editor.inputType and InputType.TYPE_MASK_CLASS
            val variation = editor.inputType and InputType.TYPE_MASK_VARIATION
            return kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
                kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        private fun content(): Pair<Int, Int> {
            val type = editor.inputType
            val kind = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            if (sensitive()) return (0x40 or 0x80) to if (kind == InputType.TYPE_CLASS_NUMBER) 9 else 8
            var hint = if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) 0x200 else 0
            if (kind == InputType.TYPE_CLASS_TEXT) {
                if (type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0) hint = hint or 3
                if (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) hint = hint and 3.inv()
                if (type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) hint = hint or 0x10
                else if (type and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) hint = hint or 0x20
                else if (type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) hint = hint or 4
            }
            if (editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) hint = hint or 0x80
            val purpose = when (kind) {
                InputType.TYPE_CLASS_NUMBER -> if (type and (InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) == 0) 2 else 3
                InputType.TYPE_CLASS_PHONE -> 4
                InputType.TYPE_CLASS_DATETIME -> when (variation) {
                    InputType.TYPE_DATETIME_VARIATION_DATE -> 10
                    InputType.TYPE_DATETIME_VARIATION_TIME -> 11
                    else -> 12
                }
                InputType.TYPE_CLASS_TEXT -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_URI -> 5
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> 6
                    InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> 7
                    else -> 0
                }
                else -> 0
            }
            return hint to purpose
        }
        fun activate() {
            if (retired || active !== this || !available || enabled) return
            task = host.task(window, uid, pid)
            if (task == null || task != focus) return
            check(nextGeneration < Long.MAX_VALUE)
            generation = ++nextGeneration
            accepted = 0; applied = 0; enabled = true
            publish(editor.getInitialSurroundingText(2000, 2000, 0), true)
            val cursor = AndroidFuture<Boolean>()
            cursorQuery = cursor
            cursor.orTimeout(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            cursor.whenComplete { _, _ -> handler.post { if (cursorQuery === cursor) cursorQuery = null } }
            connection.requestCursorUpdates(header, InputConnection.CURSOR_UPDATE_IMMEDIATE or
                InputConnection.CURSOR_UPDATE_MONITOR, android.view.Display.DEFAULT_DISPLAY, cursor)
            refresh(true)
        }
        fun suspendEditor(removePreedit: Boolean = true) {
            if (!enabled) return
            task?.let { host.publish(it, generation, applied, false, null, 0, 0, false, intArrayOf()) }
            enabled = false; generation = 0
            query?.cancel(false); query = null
            cursorQuery?.cancel(false); cursorQuery = null
            edits.clear(); applying = false; refreshPending = false
            // Wayland leave removes preedit rather than committing its characters.
            if (ownPreedit && removePreedit) {
                connection.beginBatchEdit(header)
                connection.setComposingText(header, "", 1)
                connection.finishComposingText(header)
                connection.endBatchEdit(header)
            }
            if (hiddenCaret && removePreedit) connection.setImeConsumesInput(header, false)
            hiddenCaret = false
            ownPreedit = false; composingStart = -1; composingEnd = -1
        }
        // RemoteInputConnectionImpl.deactivate -> BaseInputConnection.closeConnection
        // finishes composition and retains its text when Android switches editors.
        // The old connection is already inactive; it must not receive new edits.
        fun retire() { suspendEditor(removePreedit = false); retired = true }
        private fun fail(member: String, reason: String) {
            android.util.Log.w("WaylandIME", reason, dev.artzig.ArtzigTestDiagnostics.unsupported(
                "WaylandInputMethod", member, reason))
            suspendEditor()
        }
        private fun context(value: SurroundingText?): WaylandTextContext.Android? {
            value ?: return null
            if (ownPreedit && composingStart < 0) return null
            if (value.text.length > 8192) return null
            return WaylandTextContext.Android(value.text.toString(), value.selectionStart, value.selectionEnd,
                value.offset, composingStart, composingEnd)
        }
        private fun publish(value: SurroundingText?, external: Boolean) {
            if (!live()) return
            val plain = if (sensitive()) null else context(value)?.let(WaylandTextContext::surrounding)
            val (hint, purpose) = content()
            if (!host.publish(checkNotNull(task), generation, applied, true, plain, hint, purpose, external, rectangle))
                suspendEditor()
        }
        private fun query(action: (SurroundingText?) -> Unit) {
            check(query == null)
            val epoch = generation
            val future = AndroidFuture<SurroundingText>()
            query = future
            future.orTimeout(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            future.whenComplete { value, failure ->
                check(handler.post {
                    if (query !== future || generation != epoch || !live()) return@post
                    query = null
                    withConnection {
                        if (failure is android.os.RemoteException) throw failure
                        if (failure != null) fail("getSurroundingText", "InputConnection query failed: ${failure.javaClass.simpleName}")
                        else action(value)
                    }
                })
            }
            try { connection.getSurroundingText(header, 4000, 4000, 0, future) }
            catch (failure: android.os.RemoteException) { future.completeExceptionally(failure) }
        }
        private fun refresh(external: Boolean) {
            if (!live()) return
            if (query != null || applying) { refreshPending = true; return }
            query { value -> publish(value, external); next() }
        }
        fun enqueue(edit: Edit) {
            if (!live() || edit.generation != generation) return
            if (edit.sequence != accepted + 1 || edits.size >= 128 || edit.fields and 3 != edit.fields ||
                edit.before !in 0..4000 || edit.after !in 0..4000 ||
                WaylandTextContext.byteOffset(edit.preedit, edit.preedit.length)?.let { it <= 4000 } != true ||
                WaylandTextContext.byteOffset(edit.committed, edit.committed.length)?.let { it <= 4000 } != true) {
                fail("textInputEdit", "Invalid or overflowing text-input batch"); return
            }
            val hidden = edit.cursorBegin == -1 && edit.cursorEnd == -1
            if (!hidden && (WaylandTextContext.utf16Offset(edit.preedit, edit.cursorBegin) == null ||
                WaylandTextContext.utf16Offset(edit.preedit, edit.cursorEnd) == null)) {
                fail("preeditCursor", "Preedit cursor splits a Unicode scalar"); return
            }
            accepted = edit.sequence
            edits.add(edit)
            next()
        }
        private fun next() {
            if (!live() || query != null || applying) return
            val edit = edits.poll()
            if (edit == null) {
                if (refreshPending) { refreshPending = false; refresh(true) }
                return
            }
            applying = true
            query { value -> apply(edit, value) }
        }
        private fun apply(edit: Edit, value: SurroundingText?) {
            val snapshot = context(value)
            val deletion = if (edit.before == 0 && edit.after == 0) WaylandTextContext.Deletion(0, 0)
                else snapshot?.let { WaylandTextContext.deletion(it, edit.before, edit.after) }
            if (deletion == null) { fail("deleteSurroundingText", "Compositor deletion has no matching Unicode context"); return }
            connection.beginBatchEdit(header)
            try {
                if (hiddenCaret) connection.setImeConsumesInput(header, false)
                hiddenCaret = false
                if (ownPreedit || composingStart >= 0) {
                    connection.setComposingText(header, "", 1)
                    connection.finishComposingText(header)
                }
                if (deletion.before != 0 || deletion.after != 0)
                    connection.deleteSurroundingText(header, deletion.before, deletion.after)
                if (edit.fields and 2 != 0) connection.commitText(header, edit.committed, 1)
            } finally { connection.endBatchEdit(header) }
            ownPreedit = false; composingStart = -1; composingEnd = -1
            // This query is also the app-main-thread barrier after the preceding edits.
            query { plain ->
                if (edit.fields and 1 != 0 && edit.preedit.isNotEmpty()) {
                    val start = plain?.takeIf { it.offset >= 0 }?.let {
                        it.offset.toLong() + minOf(it.selectionStart, it.selectionEnd)
                    }
                    val hidden = edit.cursorBegin == -1 && edit.cursorEnd == -1
                    val cursor = if (hidden) edit.preedit.length else checkNotNull(WaylandTextContext.utf16Offset(edit.preedit, edit.cursorBegin))
                    val anchor = if (hidden) cursor else checkNotNull(WaylandTextContext.utf16Offset(edit.preedit, edit.cursorEnd))
                    val requiresOffset = cursor != edit.preedit.length || anchor != cursor
                    if (start != null && start !in 0..(Int.MAX_VALUE - edit.preedit.length).toLong() || start == null && requiresOffset) {
                        fail("setComposingText", "Editor does not provide a document offset for preedit selection"); return@query
                    }
                    connection.beginBatchEdit(header)
                    try {
                        connection.setComposingText(header, edit.preedit, 1)
                        if (requiresOffset) connection.setSelection(header, checkNotNull(start).toInt() + cursor, start.toInt() + anchor)
                        // InputConnection/EditableInputConnection.setImeConsumesInput
                        // is the stock IME request to hide and restore the editor caret.
                        if (hidden) connection.setImeConsumesInput(header, true)
                    } finally { connection.endBatchEdit(header) }
                    ownPreedit = true
                    hiddenCaret = hidden
                    composingStart = start?.toInt() ?: -1; composingEnd = start?.toInt()?.plus(edit.preedit.length) ?: -1
                }
                query { after ->
                    applied = edit.sequence
                    applying = false
                    publish(after, false)
                    next()
                }
            }
        }

        override fun updateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
            candidatesStart: Int, candidatesEnd: Int) = dispatch {
            composingStart = candidatesStart; composingEnd = candidatesEnd
            refresh(true)
        }
        override fun invalidateInput(info: EditorInfo, input: IRemoteInputConnection, sessionId: Int) = dispatch {
            suspendEditor()
            editor = info.createCopyInternal(); connection = input; header = InputConnectionCommandHeader(sessionId)
            activate()
        }
        override fun finishInput() = dispatch { retire() }
        override fun finishSession() = dispatch { retire() }
        override fun updateCursorAnchorInfo(info: android.view.inputmethod.CursorAnchorInfo) = dispatch {
            val points = floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerTop,
                info.insertionMarkerHorizontal, info.insertionMarkerBottom)
            info.matrix.mapPoints(points)
            rectangle = if (points.all(Float::isFinite)) task?.let { host.cursor(it, points) } ?: intArrayOf() else intArrayOf()
            refresh(true)
        }
        // BaseInputMethodSession defaults: no extraction/completion UI or app-private extension.
        override fun updateExtractedText(token: Int, text: android.view.inputmethod.ExtractedText?) = dispatch { refresh(true) }
        override fun viewClicked(focusChanged: Boolean) = dispatch { refresh(true) }
        override fun updateCursor(cursor: android.graphics.Rect?) {}
        override fun displayCompletions(completions: Array<android.view.inputmethod.CompletionInfo>?) {}
        override fun appPrivateCommand(action: String?, data: android.os.Bundle?) {}
        override fun removeImeSurface() {} // There is no Android IME surface or inset to remove.
    }
}
