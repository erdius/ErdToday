package com.erdman.erdtoday.vikunja

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class VikunjaTaskWrite(
    val title: String,
    val dueDate: Instant?,
    val done: Boolean,
)

data class VikunjaTaskRead(
    val id: Long,
    val title: String,
    val dueDate: LocalDate?,
    val done: Boolean,
    val doneAt: Instant?,
    val updatedAt: Instant,
    val labelIds: List<Long>,
)

/** Talks to one self-hosted Vikunja instance's REST API using a scoped API token. */
class VikunjaApiClient(baseUrl: String, apiToken: String) : VikunjaApi {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false // we check status codes ourselves, not via exceptions
        defaultRequest {
            header("Authorization", "Bearer $apiToken")
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
        }
    }

    private val api = "$baseUrl/api/v1"

    override suspend fun listProjects(): Result<List<VikunjaProject>> = runCatching {
        val resp = client.get("$api/projects")
        requireSuccess(resp)
        resp.body<List<VikunjaProjectJson>>().map { VikunjaProject(it.id, it.title) }
    }

    override suspend fun createProject(title: String): Result<VikunjaProject> = runCatching {
        val resp = client.put("$api/projects") {
            contentType(ContentType.Application.Json)
            setBody(VikunjaProjectJson(title = title))
        }
        requireSuccess(resp)
        val json = resp.body<VikunjaProjectJson>()
        VikunjaProject(json.id, json.title)
    }

    override suspend fun listTasks(projectId: Long): Result<List<VikunjaTaskRead>> = runCatching {
        val resp = client.get("$api/projects/$projectId/tasks")
        requireSuccess(resp)
        resp.body<List<VikunjaTaskJson>>().map { toRead(it) }
    }

    override suspend fun createTask(projectId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead> = runCatching {
        val resp = client.put("$api/projects/$projectId/tasks") {
            contentType(ContentType.Application.Json)
            setBody(toWriteJson(task))
        }
        requireSuccess(resp)
        toRead(resp.body<VikunjaTaskJson>())
    }

    override suspend fun updateTask(taskId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead> = runCatching {
        val resp = client.post("$api/tasks/$taskId") {
            contentType(ContentType.Application.Json)
            setBody(toWriteJson(task))
        }
        requireSuccess(resp)
        toRead(resp.body<VikunjaTaskJson>())
    }

    override suspend fun deleteTask(taskId: Long): Result<Unit> = runCatching {
        val resp = client.delete("$api/tasks/$taskId")
        requireSuccess(resp)
    }

    override suspend fun listLabels(): Result<List<VikunjaLabel>> = runCatching {
        val resp = client.get("$api/labels")
        requireSuccess(resp)
        resp.body<List<VikunjaLabelJson>>().map { VikunjaLabel(it.id, it.title) }
    }

    override suspend fun createLabel(title: String): Result<VikunjaLabel> = runCatching {
        val resp = client.put("$api/labels") {
            contentType(ContentType.Application.Json)
            setBody(VikunjaLabelJson(title = title))
        }
        requireSuccess(resp)
        val json = resp.body<VikunjaLabelJson>()
        VikunjaLabel(json.id, json.title)
    }

    override suspend fun addLabelToTask(taskId: Long, labelId: Long): Result<Unit> = runCatching {
        val resp = client.put("$api/tasks/$taskId/labels") {
            contentType(ContentType.Application.Json)
            setBody(VikunjaLabelTaskWriteJson(label_id = labelId))
        }
        requireSuccess(resp)
    }

    override suspend fun removeLabelFromTask(taskId: Long, labelId: Long): Result<Unit> = runCatching {
        val resp = client.delete("$api/tasks/$taskId/labels/$labelId")
        requireSuccess(resp)
    }

    private fun toWriteJson(task: VikunjaTaskWrite): VikunjaTaskWriteJson = VikunjaTaskWriteJson(
        title = task.title,
        done = task.done,
        due_date = task.dueDate ?: VIKUNJA_ZERO_TIME,
    )

    private fun toRead(json: VikunjaTaskJson): VikunjaTaskRead = VikunjaTaskRead(
        id = json.id,
        title = json.title,
        dueDate = if (json.due_date.atZone(ZoneOffset.UTC).year <= 1) null else json.due_date.atZone(ZoneOffset.UTC).toLocalDate(),
        done = json.done,
        doneAt = if (json.done_at.atZone(ZoneOffset.UTC).year <= 1) null else json.done_at,
        updatedAt = json.updated,
        // Verified live (Task 4 Step 7): the real Task JSON response DOES include a populated
        // `labels` array on read -- "read-only" per the Global Constraints means the server
        // ignores `labels` sent in a create/update *request* body, not that it's absent on
        // responses. Populate directly here rather than a separate per-task round-trip.
        labelIds = json.labels.orEmpty().map { it.id },
    )

    /** Fetches a task's current label ids from the dedicated endpoint. Not needed by [toRead]
     *  (the task read JSON already includes a populated `labels` array -- see its doc comment),
     *  but kept as a standalone utility for callers that only need label ids for one task. */
    override suspend fun fetchLabelIds(taskId: Long): Result<List<Long>> = runCatching {
        val resp = client.get("$api/tasks/$taskId/labels")
        requireSuccess(resp)
        resp.body<List<VikunjaLabelJson>>().map { it.id }
    }

    private fun requireSuccess(resp: HttpResponse) {
        check(resp.status.isSuccess()) { "Vikunja API ${resp.request.method.value} ${resp.request.url} failed: ${resp.status}" }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299
}
