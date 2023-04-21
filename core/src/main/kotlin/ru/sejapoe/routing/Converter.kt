package ru.sejapoe.routing

fun interface Converter<T> {
    fun fromString(from: String): T?
}