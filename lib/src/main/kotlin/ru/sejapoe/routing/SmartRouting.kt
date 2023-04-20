package ru.sejapoe.routing

import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlin.reflect.KClass

class KspRoutingPluginConfiguration {
    val providers = mutableMapOf<KClass<*>, Provider<*>>()
}

val KspRouting = createApplicationPlugin("SmartRouting", createConfiguration = ::KspRoutingPluginConfiguration) {
    val router =
        Class.forName("RouterImpl").kotlin.objectInstance?.let { (it as Router) } ?: return@createApplicationPlugin

    application.routing {
        router.run {
            application.registerRoutes(this@createApplicationPlugin.pluginConfig.providers)
        }
    }
}