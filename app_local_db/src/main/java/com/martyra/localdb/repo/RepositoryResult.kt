package com.martyra.localdb.repo

/**
 * A typed result wrapper for all [EvidenceRepository] operations.
 *
 * Using a sealed class instead of exceptions allows callers to handle failure cases
 * in a when-expression without needing try-catch at every call site, while still
 * preserving full error detail in [Failure].
 */
sealed class RepositoryResult<out T> {

    data class Success<T>(val data: T) : RepositoryResult<T>()

    data class Failure(val error: RepositoryError) : RepositoryResult<Nothing>()

    // ── Convenience accessors ──────────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun dataOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): RepositoryError? = (this as? Failure)?.error

    /** Throw an [IllegalStateException] if this is a failure (useful in tests). */
    fun dataOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw IllegalStateException("RepositoryResult.Failure: $error")
    }
}

/** Typed error variants returned in [RepositoryResult.Failure]. */
sealed class RepositoryError {

    /** A required field was missing, blank, or out-of-range. */
    data class ValidationError(val message: String) : RepositoryError()

    /** A database operation threw an exception. */
    data class DatabaseError(val cause: Throwable) : RepositoryError()

    /** A required file was missing, unreadable, or failed its hash check. */
    data class FileError(val message: String) : RepositoryError()

    /**
     * A security invariant was violated (path traversal, mismatched caller hash,
     * or passphrase key unavailable).
     */
    data class SecurityError(val message: String) : RepositoryError()

    /** The requested entity does not exist. */
    data class NotFound(val entityId: String) : RepositoryError()

    /**
     * The requested status transition is not valid.
     * [from] is the current status; [to] is the rejected target.
     */
    data class InvalidStateTransition(val from: String, val to: String) : RepositoryError()
}
