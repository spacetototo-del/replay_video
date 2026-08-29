package com.diving.replay.util

import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Minimal await for the ListenableFuture that CameraX's ProcessCameraProvider returns. */
suspend fun <T> ListenableFuture<T>.awaitCompat(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: Throwable) {
            cont.resumeWithException(e)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(false) }
}
