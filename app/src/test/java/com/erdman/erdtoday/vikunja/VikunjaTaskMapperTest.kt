package com.erdman.erdtoday.vikunja

import com.erdman.erdtoday.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class VikunjaTaskMapperTest {

    @Test fun `toWrite maps title, deadline, and completed`() {
        val task = TaskEntity(id = 1, title = "Buy milk", deadline = LocalDate.of(2026, 9, 5), completed = true)
        val write = VikunjaTaskMapper.toWrite(task)
        assertEquals("Buy milk", write.title)
        assertEquals(LocalDate.of(2026, 9, 5).atStartOfDay(ZoneOffset.UTC).toInstant(), write.dueDate)
        assertEquals(true, write.done)
    }

    @Test fun `toWrite maps a null deadline to a null dueDate`() {
        val task = TaskEntity(id = 1, title = "No deadline", deadline = null)
        assertNull(VikunjaTaskMapper.toWrite(task).dueDate)
    }

    @Test fun `applyRead updates synced fields and preserves local-only fields`() {
        val local = TaskEntity(
            id = 1, title = "old title", notes = "keep me", deadline = null, completed = false,
            scheduledDate = LocalDate.of(2026, 9, 1),
        )
        val read = VikunjaTaskRead(
            id = 42, title = "new title from server", dueDate = LocalDate.of(2026, 9, 10),
            done = true, doneAt = Instant.parse("2026-09-01T12:00:00Z"),
            updatedAt = Instant.parse("2026-09-01T12:00:00Z"), labelIds = emptyList(),
        )
        val result = VikunjaTaskMapper.applyRead(read, local)
        assertEquals("new title from server", result.title)
        assertEquals(LocalDate.of(2026, 9, 10), result.deadline)
        assertEquals(true, result.completed)
        assertEquals(42L, result.vikunjaTaskId)
        assertEquals("keep me", result.notes) // local-only, preserved
        assertEquals(LocalDate.of(2026, 9, 1), result.scheduledDate) // local-only, preserved
    }
}
