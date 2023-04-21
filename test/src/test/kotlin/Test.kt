import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.testing.*
import io.ktor.util.*
import org.junit.jupiter.api.Test
import ru.sejapoe.routing.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.test.assertEquals

@Route("/test")
object TestRouting {
    @Get("/getWithoutParamsReturningString")
    fun getWithoutParamsReturningString() = "nice"

    @Test
    fun `get without params, returning string`(): Unit = testApplication {
        val response = client.get("/test/getWithoutParamsReturningString")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice", response.bodyAsText())
    }

    @Test
    fun `get unexpected route`(): Unit = testApplication {
        val response = client.get("/test/unknown")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Get("/getThrowingException")
    fun getThrowingException() {
        throw NotFoundException()
    }

    @Test
    fun `get throwing exception`(): Unit = testApplication {
        val response = client.get("/test/getThrowingException")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Get("/getWithIntPathParam/{id}")
    fun getWithIntPathParam(id: Int) = "nice $id"

    @Test
    fun `get with int path param`(): Unit = testApplication {
        val response = client.get("/test/getWithIntPathParam/1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice 1", response.bodyAsText())
    }

    @Get("/getRegisteredConverterPathParam/{date}")
    fun getRegisteredConverterPathParam(date: LocalDate) = "nice ${date.dayOfYear}"

    @Test
    fun `get registered converter path param`(): Unit = testApplication {
        val response = client.get("/test/getRegisteredConverterPathParam/2021-01-01")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice 1", response.bodyAsText())
    }

    @Test
    fun `get registered converted path param, not convertable`(): Unit = testApplication {
        val response = client.get("/test/getRegisteredConverterPathParam/2021-01-01-01")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Get("/getUnregisteredConverterPathParam/{exception}")
    fun getUnregisteredConverterPathParam(exception: Exception) = "nice ${exception.message}"

    @Test
    fun `get unregistered converter path param`(): Unit = testApplication {
        val response = client.get("/test/getUnregisteredConverterPathParam/abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    object ExceptionConverter : Converter<Exception> {
        override fun fromString(from: String) = Exception(from)
    }

    @Get("/getAnnotatedConverterPathParam/{exception}")
    fun getAnnotatedConverterPathParam(@Convert(ExceptionConverter::class) exception: Exception) = "nice ${exception.message}"

    @Test
    fun `get annotated converter path param`(): Unit = testApplication {
        val response = client.get("/test/getAnnotatedConverterPathParam/abc")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice abc", response.bodyAsText())
    }
}

@KtorDsl
fun testApplication(foo: @KtorDsl suspend ApplicationTestBuilder.() -> Unit) {
    io.ktor.server.testing.testApplication {
        install(KspRouting) {
            registerConverter {
                try {
                    LocalDate.parse(it)
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        }
        foo()
    }
}
