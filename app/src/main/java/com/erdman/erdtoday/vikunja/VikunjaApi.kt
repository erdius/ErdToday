package com.erdman.erdtoday.vikunja

/**
 * The subset of Vikunja's REST API that [SyncEngine]/[VikunjaProjectSetup] depend on, extracted
 * purely so tests can supply a fake implementation instead of driving [VikunjaApiClient]'s real
 * network calls. [VikunjaApiClient] is the only production implementation.
 */
interface VikunjaApi {
    suspend fun listProjects(): Result<List<VikunjaProject>>
    suspend fun createProject(title: String): Result<VikunjaProject>
    suspend fun listTasks(projectId: Long): Result<List<VikunjaTaskRead>>
    suspend fun createTask(projectId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead>
    suspend fun updateTask(taskId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead>
    suspend fun deleteTask(taskId: Long): Result<Unit>
    suspend fun listLabels(): Result<List<VikunjaLabel>>
    suspend fun createLabel(title: String): Result<VikunjaLabel>
    suspend fun addLabelToTask(taskId: Long, labelId: Long): Result<Unit>
    suspend fun removeLabelFromTask(taskId: Long, labelId: Long): Result<Unit>
    suspend fun fetchLabelIds(taskId: Long): Result<List<Long>>
}
