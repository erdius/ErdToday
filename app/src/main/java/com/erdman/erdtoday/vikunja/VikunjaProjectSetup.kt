package com.erdman.erdtoday.vikunja

object VikunjaProjectSetup {

    private const val PROJECT_TITLE = "ErdToday"

    /** Finds the existing "ErdToday" project by title, or creates it if none exists yet. */
    suspend fun findOrCreateErdTodayProject(api: VikunjaApi): Result<Long> = runCatchingCancellable {
        val existing = api.listProjects().getOrThrow().firstOrNull { it.title == PROJECT_TITLE }
        existing?.id ?: api.createProject(PROJECT_TITLE).getOrThrow().id
    }
}

/** Like [runCatching], but never swallows [kotlinx.coroutines.CancellationException] --
 *  cancellation must propagate to unwind the coroutine, not become an ordinary Result.failure. */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
