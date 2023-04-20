package ru.sejapoe.routing

import io.ktor.server.application.*
import kotlin.reflect.KClass

interface Router {
    fun Application.registerRoutes(providers: Map<KClass<*>, Provider<*>>)
}