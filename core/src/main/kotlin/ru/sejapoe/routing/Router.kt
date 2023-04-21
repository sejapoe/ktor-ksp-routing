package ru.sejapoe.routing

import io.ktor.server.application.*

interface Router {
    fun Application.registerRoutes(providers: ProviderRegistry, converters: ConverterRegistry)
}