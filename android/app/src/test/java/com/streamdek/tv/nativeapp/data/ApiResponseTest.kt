package com.streamdek.tv.nativeapp.data

import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ApiResponseTest {
    private fun response(body: ResponseBody, code: Int = 200): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()

    @Test fun timeoutAfterSuccessHeadersIsFailureAndClosesBody() {
        val timeout = SocketTimeoutException("fixture")
        var closed = false
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw timeout
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val result = readApiResponse { response(body) }
        assertSame(timeout, result.exceptionOrNull())
        assertTrue(closed)
    }

    @Test fun openingTimeoutIsAlsoTransportFailure() {
        val timeout = SocketTimeoutException("fixture")
        assertSame(timeout, readApiResponse { throw timeout }.exceptionOrNull())
    }

    @Test fun preservesHttpErrorsAndEmptySuccess() {
        assertEquals(ApiResponse(403, "denied"), readApiResponse { response("denied".toResponseBody(), 403) }.getOrThrow())
        assertEquals(ApiResponse(204, ""), readApiResponse { response("".toResponseBody(), 204) }.getOrThrow())
    }

    @Test fun cancellationAndProgrammingFailuresAreNotConvertedToNetworkFailures() {
        assertThrows(CancellationException::class.java) { readApiResponse { throw CancellationException() } }
        assertThrows(IllegalStateException::class.java) { readApiResponse { throw IllegalStateException() } }
    }
}
