package com.erdman.erdtoday.caldav

import com.erdman.erdtoday.data.local.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE // yyyyMMdd, per RFC 5545 DATE value type
private val UTC_STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

data class ParsedVTodo(
    val uid: String,
    val title: String,
    val scheduledDate: LocalDate?,
    val deadline: LocalDate?,
    val completed: Boolean,
    val completedAt: Instant?,
    val tagNames: List<String>,
)

object VTodoMapper {

    fun toVTodoText(task: TaskEntity, tagNames: List<String>): String {
        require(task.caldavUid != null) { "toVTodoText requires a task with caldavUid already assigned" }
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//erdman//ErdToday//EN",
            "BEGIN:VTODO",
            "UID:${task.caldavUid}",
            "SUMMARY:${escapeText(task.title)}",
        )
        task.scheduledDate?.let { lines += "DTSTART;VALUE=DATE:${it.format(DATE_FMT)}" }
        task.deadline?.let { lines += "DUE;VALUE=DATE:${it.format(DATE_FMT)}" }
        lines += "STATUS:${if (task.completed) "COMPLETED" else "NEEDS-ACTION"}"
        if (task.completed) {
            val completedInstant = task.completedAt ?: Instant.now()
            lines += "COMPLETED:${UTC_STAMP_FMT.format(completedInstant.atZone(java.time.ZoneOffset.UTC))}"
        }
        if (tagNames.isNotEmpty()) {
            lines += "CATEGORIES:${tagNames.joinToString(",") { escapeText(it) }}"
        }
        lines += listOf("END:VTODO", "END:VCALENDAR")
        return lines.joinToString("\r\n") // RFC 5545 requires CRLF line endings
    }

    fun parseVTodo(icalText: String): ParsedVTodo {
        // Unfold: a line starting with a space/tab is a continuation of the previous line.
        val unfolded = icalText.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
        var uid: String? = null
        var title = ""
        var scheduledDate: LocalDate? = null
        var deadline: LocalDate? = null
        var status = "NEEDS-ACTION"
        var completedAt: Instant? = null
        var tagNames: List<String> = emptyList()

        for (rawLine in unfolded.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val nameAndParams = line.substring(0, colonIdx)
            val rawValue = line.substring(colonIdx + 1)
            val name = nameAndParams.substringBefore(';')
            when (name) {
                "UID" -> uid = unescapeText(rawValue)
                "SUMMARY" -> title = unescapeText(rawValue)
                "DTSTART" -> scheduledDate = parseDateValue(rawValue)
                "DUE" -> deadline = parseDateValue(rawValue)
                "STATUS" -> status = rawValue
                "COMPLETED" -> completedAt = parseUtcStamp(rawValue)
                "CATEGORIES" -> tagNames = splitEscapedList(rawValue)
            }
        }
        return ParsedVTodo(
            uid = requireNotNull(uid) { "VTODO has no UID" },
            title = title,
            scheduledDate = scheduledDate,
            deadline = deadline,
            completed = status == "COMPLETED",
            completedAt = completedAt,
            tagNames = tagNames,
        )
    }

    /** RFC 5545 §3.3.11 TEXT escaping: backslash, semicolon, comma, and newline. */
    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    private fun parseDateValue(value: String): LocalDate =
        LocalDate.parse(value.take(8), DATE_FMT) // tolerate a trailing time component if a server sends DATE-TIME

    private fun parseUtcStamp(value: String): Instant =
        java.time.LocalDateTime.parse(value, UTC_STAMP_FMT).toInstant(java.time.ZoneOffset.UTC)

    private fun unescapeText(s: String): String = s
        .replace("\\n", "\n").replace("\\N", "\n")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /**
     * Splits an RFC 5545 escaped, comma-separated value list on unescaped commas only —
     * a comma preceded by a backslash (part of one item's escaped literal comma) is kept
     * intact, not treated as a separator. Each resulting token is then unescaped individually.
     */
    private fun splitEscapedList(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                current.append(c).append(raw[i + 1])
                i += 2
            } else if (c == ',') {
                tokens += current.toString()
                current.clear()
                i++
            } else {
                current.append(c)
                i++
            }
        }
        tokens += current.toString()
        return tokens.map { unescapeText(it) }.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
