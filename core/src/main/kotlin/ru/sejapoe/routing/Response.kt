package ru.sejapoe.routing

import io.ktor.http.*

class Response<T>(val status: HttpStatusCode = HttpStatusCode.OK, val data: T? = null) {
    val isSuccessful: Boolean
        get() = data != null
}
