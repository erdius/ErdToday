package com.erdman.erdtoday.vikunja

object VikunjaProjectSetup {

    private const val PROJECT_TITLE = "ErdToday"

    /** Finds the existing "ErdToday" project by title, or creates it if none exists yet. */
    suspend fun findOrCreateErdTodayProject(api: VikunjaApi): Result<Long> = runCatching {
        val existing = api.listProjects().getOrThrow().firstOrNull { it.title == PROJECT_TITLE }
        existing?.id ?: api.createProject(PROJECT_TITLE).getOrThrow().id
    }
}
