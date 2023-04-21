package ru.sejapoe.routing

import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlin.reflect.KClass

class ProviderRegistry internal constructor() {
    val providers = mutableMapOf<KClass<*>, Provider<*>>()

    inline fun <reified T : Any> add(provider: Provider<T>) {
        providers[T::class] = provider
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> get(): Provider<T>? {
        return providers[T::class] as? Provider<T>
    }
}

class ConverterRegistry internal constructor() {
    val converters = mutableMapOf<KClass<*>, Converter<*>>()

    init {
        add(String::toString)
        add(String::toIntOrNull)
        add(String::toLongOrNull)
        add(String::toFloatOrNull)
        add(String::toDoubleOrNull)
        add(String::toBoolean)
    }

    inline fun <reified T : Any> add(converter: Converter<T>) {
        converters[T::class] = converter
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> get(): Converter<T>? {
        return converters[T::class] as? Converter<T>
    }
}


class KspRoutingPluginConfiguration {
    val providers = ProviderRegistry()
    val converters = ConverterRegistry()

    inline fun <reified T : Any> registerProvider(provider: Provider<T>) {
        providers.add(provider)
    }

    inline fun <reified T : Any> registerConverter(converter: Converter<T>) {
        converters.add(converter)
    }
}

val KspRouting = createApplicationPlugin("SmartRouting", createConfiguration = ::KspRoutingPluginConfiguration) {
    val router =
        Class.forName("RouterImpl").kotlin.objectInstance?.let { (it as Router) } ?: return@createApplicationPlugin

    val providers = pluginConfig.providers
    val converters = pluginConfig.converters
    application.routing {
        router.run {
            application.registerRoutes(providers, converters)
        }
    }
}