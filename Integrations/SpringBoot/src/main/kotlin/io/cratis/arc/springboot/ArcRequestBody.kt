// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.http.HttpServletRequest
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal class ArcRequestBodyTooLargeException : IOException("The Arc request body exceeds the configured limit.")

internal fun boundedRequestBody(request: HttpServletRequest, maximumBytes: Long): InputStream {
    if (request.contentLengthLong > maximumBytes) throw ArcRequestBodyTooLargeException()
    return CountingRequestInputStream(request.inputStream, maximumBytes)
}

internal fun Throwable.isArcRequestBodyTooLarge(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ArcRequestBodyTooLargeException) return true
        if (current.cause === current) break
        current = current.cause
    }
    return false
}

private class CountingRequestInputStream(
    input: InputStream,
    private val maximumBytes: Long
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) record(read.toLong())
        return read
    }

    private fun record(bytes: Long) {
        count += bytes
        if (count > maximumBytes) throw ArcRequestBodyTooLargeException()
    }
}
