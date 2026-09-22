package com.streamdek.tv.nativeapp.data

import java.io.IOException
import okhttp3.Response

internal data class ApiResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Headers arriving does not finish a call: reading or closing its body can still fail. */
internal fun readApiResponse(execute: () -> Response): Result<ApiResponse> = try {
    Result.success(execute().use { ApiResponse(it.code, it.body?.string().orEmpty()) })
} catch (error: IOException) {
    Result.failure(error)
}
