package ru.sejapoe.routing

import io.ktor.server.application.*

interface Provider<T> {
    suspend fun provide(call: ApplicationCall): T
}