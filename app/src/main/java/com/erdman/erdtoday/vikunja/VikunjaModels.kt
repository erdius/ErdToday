package com.erdman.erdtoday.vikunja

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/** RFC3339 <-> [Instant], as Vikunja (a Go server) encodes every timestamp field. */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
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
