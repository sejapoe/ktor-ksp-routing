package ru.sejapoe.routing

import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlin.reflect.KClass

class ProviderRegistry {
    val providers = mutableMapOf<KClass<*>, Provider<*>>()

    inline fun <reified T : Any> add(provider: Provider<T>) {
        providers[T::class] = provider
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> get(): Provider<T>? {
        return providers[T::class] as? Provider<T>
    }
}


class KspRoutingPluginConfiguration {
    val providers = ProviderRegistry()
    inline fun <reified T : Any> registerProvider(provider: Provider<T>) {
        providers.add(provider)
    }
}

val KspRouting = createApplicationPlugin("SmartRouting", createConfiguration = ::KspRoutingPluginConfiguration) {
    val router =
        Class.forName("RouterImpl").kotlin.objectInstance?.let { (it as Router) } ?: return@createApplicationPlugin

    val providers = pluginConfig.providers
    application.routing {
        router.run {
            application.registerRoutes(providers)
        }
    }
}