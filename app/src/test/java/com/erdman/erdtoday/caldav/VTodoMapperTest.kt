package com.erdman.erdtoday.caldav

import com.erdman.erdtoday.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class VTodoMapperTest {
    @Test fun `round-trips a task with all fields set`() {
        val task = TaskEntity(
            id = 1, title = "Buy milk", caldavUid = "abc-123",
            scheduledDate = LocalDate.of(2026, 9, 1),
            deadline = LocalDate.of(2026, 9, 3),
            completed = true, completedAt = Instant.parse("2026-08-31T12:00:00Z"),
        )
        val text = VTodoMapper.toVTodoText(task, tagNames = listOf("errand", "home"))
        val parsed = VTodoMapper.parseVTodo(text)

        assertEquals("abc-123", parsed.uid)
        assertEquals("Buy milk", parsed.title)
        assertEquals(LocalDate.of(2026, 9, 1), parsed.scheduledDate)
        assertEquals(LocalDate.of(2026, 9, 3), parsed.deadline)
        assertTrue(parsed.completed)
        assertEquals(Instant.parse("2026-08-31T12:00:00Z"), parsed.completedAt)
        assertEquals(listOf("errand", "home"), parsed.tagNames)
    }

    @Test fun `round-trips a minimal task with no dates, no tags, not completed`() {
        val task = TaskEntity(
            id = 1, title = "Minimal task", caldavUid = "min-001",
            scheduledDate = null,
            deadline = null,
            completed = false, completedAt = null,
        )
        val text = VTodoMapper.toVTodoText(task, tagNames = emptyList())
        val parsed = VTodoMapper.parseVTodo(text)

        assertEquals("min-001", parsed.uid)
        assertEquals("Minimal task", parsed.title)
        assertNull(parsed.scheduledDate)
        assertNull(parsed.deadline)
        assertFalse(parsed.completed)
        assertNull(parsed.completedAt)
        assertEquals(emptyList<String>(), parsed.tagNames)
    }

    @Test fun `escapes and unescapes commas, semicolons, and newlines in title and tags`() {
        val task = TaskEntity(
            id = 1, title = "Task with, semicolon; and\nnewline", caldavUid = "esc-001",
            completed = false,
        )
        val tagNames = listOf("tag,with,comma", "tag;with;semicolon", "tag\nwith\nnewline")
        val text = VTodoMapper.toVTodoText(task, tagNames = tagNames)
        val parsed = VTodoMapper.parseVTodo(text)

        assertEquals("esc-001", parsed.uid)
        assertEquals("Task with, semicolon; and\nnewline", parsed.title)
        assertEquals(tagNames, parsed.tagNames)
    }

    @Test fun `handles line-folded input from a real server`() {
        // A SUMMARY long enough that a real CalDAV server would fold it across lines,
        // continuation lines prefixed with a single space, per RFC 5545 §3.1.
        val folded = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n" +
            "UID:xyz\r\nSUMMARY:This is a very long title that a real server\r\n would fold across multiple lines\r\n" +
            "STATUS:NEEDS-ACTION\r\nEND:VTODO\r\nEND:VCALENDAR"
        val parsed = VTodoMapper.parseVTodo(folded)
        assertEquals("This is a very long title that a real serverwould fold across multiple lines", parsed.title)
    }
}
