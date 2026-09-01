package com.erdman.erdtoday.vikunja

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime

/** RFC3339 <-> [Instant], as Vikunja (a Go server) encodes every timestamp field.
 *
 *  Deserialization deliberately goes through [OffsetDateTime.parse] rather than [Instant.parse]:
 *  `Instant.parse` uses `DateTimeFormatter.ISO_INSTANT`, which requires a literal `Z` UTC
 *  designator and rejects any string with an explicit numeric offset. Vikunja's Go backend emits
 *  RFC3339 timestamps in the server process's local zone (verified live against the real
 *  dev/verification server: `created`/`updated`/`due_date` came back as e.g.
 *  `2026-09-04T20:00:00-04:00` and `2026-09-01T08:46:07.491093359-04:00`), not always the `Z`
 *  sentinel -- only [VIKUNJA_ZERO_TIME]'s Go zero-value happens to use `Z`. `Instant.parse`
 *  threw `DateTimeParseException` on every real (non-sentinel) timestamp, silently failing
 *  every push and pull. `OffsetDateTime.parse` accepts both the `Z` designator and explicit
 *  `+HH:MM`/`-HH:MM` offsets (per `DateTimeFormatter.ISO_OFFSET_DATE_TIME`), so this covers both
 *  the zero-sentinel and every real server timestamp. */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
}

/** Go's zero time.Time, the sentinel Vikunja sends for "unset" on fields with no `omitempty`. */
val VIKUNJA_ZERO_TIME: Instant = Instant.parse("0001-01-01T00:00:00Z")

/**
 * Wire type for *reading* a task. Deliberately NOT reused for writing (see [VikunjaTaskWriteJson])
 * -- verified empirically against the real server (Task 4 live verification) that a task
 * create/update body encoding this class's zero-value defaults breaks things in ways the Vikunja
 * API doesn't reject as a validation error: `project_id: 0` on create -> `403 Forbidden`; `id: 0`
 * on update -> `404 Not Found` ("This task does not exist" -- the update handler looks the task up
 * by the body's `id` when present, not just the URL path param); an explicit `done_at` zero-time
 * sentinel on update -> silently suppresses the server's own auto-computed completion timestamp
 * even when `done: true` is set in the same request (confirmed: omitting `done_at` entirely lets
 * the server populate a real timestamp; sending the zero sentinel explicitly does not). `project_id`
 * is dropped from this class entirely since neither read nor write path needs it.
 */
@Serializable
data class VikunjaTaskJson(
    val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    @Serializable(with = InstantIso8601Serializer::class)
    val done_at: Instant = VIKUNJA_ZERO_TIME,
    @Serializable(with = InstantIso8601Serializer::class)
    val due_date: Instant = VIKUNJA_ZERO_TIME,
    @Serializable(with = InstantIso8601Serializer::class)
    val updated: Instant = VIKUNJA_ZERO_TIME,
    // Vikunja sends a literal JSON `null` here (not `[]`) for a task with no labels -- a default
    // value alone doesn't cover that (defaults only apply to a *missing* key, not an explicit
    // null), so this must be nullable; see toRead()'s `json.labels.orEmpty()`.
    val labels: List<VikunjaLabelJson>? = null,
)

/**
 * Wire type for *writing* (create/update) a task. Deliberately excludes `id`, `project_id`, and
 * `done_at` -- see [VikunjaTaskJson]'s doc comment for the empirically-verified reasons each of
 * those breaks a task create/update request when included, even at its Go zero value.
 */
@Serializable
data class VikunjaTaskWriteJson(
    val title: String,
    val done: Boolean = false,
    @Serializable(with = InstantIso8601Serializer::class)
    val due_date: Instant = VIKUNJA_ZERO_TIME,
)

@Serializable
data class VikunjaLabelJson(
    val id: Long = 0,
    val title: String,
)

@Serializable
data class VikunjaProjectJson(
    val id: Long = 0,
    val title: String,
)

@Serializable
data class VikunjaLabelTaskWriteJson(
    val label_id: Long,
)

data class VikunjaProject(val id: Long, val title: String)
data class VikunjaLabel(val id: Long, val title: String)
