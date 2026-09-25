package knitty.filesystem

import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class ExclusiveFileLockTest {
    @Test
    fun contentionUsesCallerFailureAndReleasesTheLock() {
        val directory = createTempDirectory("knitty-lock-")
        val file = directory.resolve("operation.lock")

        try {
            withExclusiveFileLock(file, onBusy = { fail("First acquisition must succeed") }) {
                assertFailsWith<Busy> {
                    withExclusiveFileLock(file, onBusy = { throw Busy() }) {
                        fail("A competing operation must not run")
                    }
                }
            }

            val result = withExclusiveFileLock(file, onBusy = { fail("Lock was not released") }) { "acquired" }
            assertEquals("acquired", result)
        } finally {
            file.deleteIfExists()
            directory.deleteExisting()
        }
    }

    @Test
    fun failedActionDoesNotLeaveTheLockHeld() {
        val directory = createTempDirectory("knitty-lock-")
        val file = directory.resolve("operation.lock")

        try {
            assertFailsWith<InterruptedOperation> {
                withExclusiveFileLock(file, onBusy = { fail("First acquisition must succeed") }) {
                    throw InterruptedOperation()
                }
            }

            val result = withExclusiveFileLock(file, onBusy = { fail("Lock was not released") }) { "acquired" }
            assertEquals("acquired", result)
        } finally {
            file.deleteIfExists()
            directory.deleteExisting()
        }
    }
}

private class Busy : Exception()
private class InterruptedOperation : Exception()
