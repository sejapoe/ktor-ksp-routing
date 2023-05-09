import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import org.junit.jupiter.api.Test
import ru.sejapoe.routing.*
import ru.sejapoe.routing.Pipeline
import ru.sejapoe.routing.Route
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
    fun getAnnotatedConverterPathParam(@Convert(ExceptionConverter::class) exception: Exception) =
        "nice ${exception.message}"

    @Test
    fun `get annotated converter path param`(): Unit = testApplication {
        val response = client.get("/test/getAnnotatedConverterPathParam/abc")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice abc", response.bodyAsText())
    }

    @Get("/getIntQueryParam")
    fun getIntQueryParam(@Query id: Int) = "nice $id"

    @Test
    fun `get int query param`(): Unit = testApplication {
        val response = client.get("/test/getIntQueryParam?id=1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice 1", response.bodyAsText())
    }

    @Get("/getIntQueryParamNotRequired")
    fun getIntQueryParamNotRequired(@Query id: Int?) = "nice $id"

    @Test
    fun `get int query param not required, not provided`(): Unit = testApplication {
        val response = client.get("/test/getIntQueryParamNotRequired")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice null", response.bodyAsText())
    }

    @Test
    fun `get int query param not required, provided`(): Unit = testApplication {
        val response = client.get("/test/getIntQueryParamNotRequired?id=1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice 1", response.bodyAsText())
    }

    @Get("/getStringQueryParamWithCustomName")
    fun getStringQueryParamWithCustomName(@Query("str") id: String) = "nice $id"

    @Test
    fun `get string query param with custom name`(): Unit = testApplication {
        val response = client.get("/test/getStringQueryParamWithCustomName?str=abc")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice abc", response.bodyAsText())
    }

    @Get("/getHeaderIntParam")
    fun getHeaderIntParam(@Header id: Int) = "nice $id"

    @Test
    fun `get header int param`(): Unit = testApplication {
        val response = client.get("/test/getHeaderIntParam") {
            headers.append("id", "1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice 1", response.bodyAsText())
    }

    @Get("/getHeaderStringParamNotRequired")
    fun getHeaderStringParamNotRequired(@Header str: String?) = "nice $str"

    @Test
    fun `get header string param not required, not provided`(): Unit = testApplication {
        val response = client.get("/test/getHeaderStringParamNotRequired")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice null", response.bodyAsText())
    }

    @Test
    fun `get header string param not required, provided`(): Unit = testApplication {
        val response = client.get("/test/getHeaderStringParamNotRequired") {
            headers.append("str", "ass")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice ass", response.bodyAsText())
    }

    @Get("/getHeaderStringWithCustomName")
    fun getHeaderStringWithCustomName(@Header("str") totallyNotStr: String) = "nice $totallyNotStr"

    @Test
    fun `get header string with custom name`() = testApplication {
        val response = client.get("/test/getHeaderStringWithCustomName") {
            headers.append("str", "ass")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice ass", response.bodyAsText())
    }

    @Get("/getPipelineContext")
    fun getPipelineContext(@Pipeline pipeline: PipelineContext<Unit, ApplicationCall>) =
        "nice ${pipeline.call.javaClass.simpleName}"

    @Test
    fun `get pipeline context`() = testApplication {
        val response = client.get("/test/getPipelineContext")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice ${RoutingApplicationCall::class.java.simpleName}", response.bodyAsText())
    }

    @Get("/getProviderFirstArg/{inc}")
    fun getProviderFirstArg(@Provided provided: TestProvided, inc: Int) = "nice ${provided.value + inc}"

    @Test
    fun `get provider first argument`() = testApplication {
        val inc = 6
        val response = client.get("/test/getProviderFirstArg/$inc")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice ${42 + inc}", response.bodyAsText())
    }
}

data class TestProvided(val value: Int)

class TestProvider : Provider<TestProvided> {
    override suspend fun provide(call: ApplicationCall) =
        TestProvided(42)
}

@KtorDsl
fun testApplication(foo: @KtorDsl suspend ApplicationTestBuilder.() -> Unit) {
    io.ktor.server.testing.testApplication {
        install(KspRouting) {
            registerProvider(TestProvider())
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
