package ru.sejapoe.routing

interface ParameterConverter<T> {
    fun toString(from: T): String
    fun fromString(from: String): T
}