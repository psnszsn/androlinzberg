package dev.artzig.window

import java.util.Random

/** Interface-level tests for task reservation, async acknowledgement and reuse. */
object TaskGeometrySmoke {
    private fun TaskGeometry.change(task: TaskGeometry.Task, width: Int, height: Int) =
        prepare(task, width, height) as TaskGeometry.Proposal.Change

    @JvmStatic fun main(args: Array<String>) {
        val a = TaskGeometry.Task(1, 1)
        val b = TaskGeometry.Task(2, 1)
        val c = TaskGeometry.Task(3, 1)
        val geometry = TaskGeometry(100, 100, capacity = 3, gap = 0)
        val initial = geometry.change(a, 40, 40)
        check(geometry.bounds(a) == null && geometry.commit(initial))
        check(!geometry.commit(initial))
        val peer = geometry.change(b, 60, 100)
        check(peer.after == TaskGeometry.Bounds(40, 0, 60, 100))
        check(geometry.commit(peer))
        val resize = geometry.change(a, 40, 80)
        check(resize.after.left == 0 && resize.after.top == 0)
        check(geometry.prepare(a, 20, 20) ==
            TaskGeometry.Proposal.Rejected(TaskGeometry.Rejection.TRANSACTION_PENDING))
        check(geometry.cancel(resize) && geometry.bounds(a) == initial.after)
        check(geometry.prepare(c, 50, 50) ==
            TaskGeometry.Proposal.Rejected(TaskGeometry.Rejection.NO_SPACE))
        check(geometry.prepare(c, Int.MAX_VALUE, 1) ==
            TaskGeometry.Proposal.Rejected(TaskGeometry.Rejection.TOO_LARGE))
        val pending = geometry.change(c, 40, 60)
        check(pending.after == TaskGeometry.Bounds(0, 40, 40, 60))
        check(geometry.prepare(TaskGeometry.Task(4, 1), 1, 1) ==
            TaskGeometry.Proposal.Rejected(TaskGeometry.Rejection.CAPACITY))
        check(geometry.remove(c) && !geometry.commit(pending))
        val replacement = TaskGeometry.Task(c.id, 2)
        val restored = geometry.prepare(replacement, 40, 60, pending.after) as TaskGeometry.Proposal.Change
        check(restored.after == pending.after && geometry.commit(restored))
        check(!geometry.remove(c) && !geometry.cancel(pending))
        check(geometry.prepare(c, 1, 1) ==
            TaskGeometry.Proposal.Rejected(TaskGeometry.Rejection.STALE_TASK))

        // Relocation reserves both old and new footprints until acknowledgement.
        val moves = TaskGeometry(100, 100, gap = 0)
        val first = moves.change(a, 50, 50).also { check(moves.commit(it)) }
        moves.change(b, 50, 50).also { check(moves.commit(it)) }
        val move = moves.change(a, 100, 50)
        check(move.after == TaskGeometry.Bounds(0, 50, 100, 50))
        check(moves.prepare(c, 50, 50) ==
            TaskGeometry.Proposal.Rejected(TaskGeometry.Rejection.NO_SPACE))
        check(moves.commit(move))
        val reuse = moves.change(c, 50, 50)
        check(reuse.after == first.after && moves.commit(reuse))

        randomized()
        println("TaskGeometry: bounded placement, resize, reservations, restore and stale generations passed")
    }

    private fun randomized() {
        val random = Random(0x617a6b)
        val allocator = TaskGeometry(128, 96, 24, gap = 1)
        val current = linkedMapOf<TaskGeometry.Task, TaskGeometry.Bounds>()
        var generation = 1L
        repeat(2000) {
            val existing = current.keys.toList()
            val task = if (existing.isNotEmpty() && random.nextBoolean())
                existing[random.nextInt(existing.size)] else TaskGeometry.Task(it + 100, generation++)
            if (current.containsKey(task) && random.nextInt(4) == 0) {
                check(allocator.remove(task))
                current.remove(task)
            } else {
                val before = current[task]
                when (val proposal = allocator.prepare(task, random.nextInt(60) + 1, random.nextInt(60) + 1)) {
                    is TaskGeometry.Proposal.Rejected -> check(allocator.bounds(task) == before)
                    is TaskGeometry.Proposal.Change -> {
                        check(proposal.before == before)
                        if (random.nextInt(4) == 0) {
                            check(allocator.cancel(proposal) && allocator.bounds(task) == before)
                        } else {
                            check(allocator.commit(proposal))
                            current[task] = proposal.after
                        }
                    }
                }
            }
            val bounds = current.values.toList()
            for ((index, rect) in bounds.withIndex()) {
                check(rect.left >= 0 && rect.top >= 0 && rect.right <= 128 && rect.bottom <= 96)
                for (other in bounds.drop(index + 1)) check(
                    rect.right < other.left || other.right < rect.left ||
                        rect.bottom < other.top || other.bottom < rect.top,
                )
            }
            for ((key, rect) in current) check(allocator.bounds(key) == rect)
        }
        // Corrupt saved origins cannot wrap around into the display.
        val overflow = TaskGeometry.Bounds(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        val fresh = TaskGeometry(32, 32)
        val placement = fresh.prepare(TaskGeometry.Task(1, 1), 32, 32, overflow) as TaskGeometry.Proposal.Change
        check(placement.after == TaskGeometry.Bounds(0, 0, 32, 32))
    }
}
