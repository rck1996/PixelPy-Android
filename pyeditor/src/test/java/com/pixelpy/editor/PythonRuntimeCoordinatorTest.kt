package com.pixelpy.editor

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class PythonRuntimeCoordinatorTest {
    @Test
    fun coordinatorKeepsOneMutexAndSerializesAllCallers() = runBlocking {
        assertSame(PythonRuntimeCoordinator.mutex, PythonRuntimeCoordinator.mutex)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        val callers = List(3) {
            async {
                PythonRuntimeCoordinator.runExclusive {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, now) }
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }
        callers.forEach { it.await() }

        assertEquals(1, maximum.get())
        assertFalse(PythonRuntimeCoordinator.busy.value)
    }
}
