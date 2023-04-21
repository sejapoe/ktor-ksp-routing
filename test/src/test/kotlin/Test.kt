import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.testing.*
import io.ktor.util.*
import org.junit.jupiter.api.Test
import ru.sejapoe.routing.Get
import ru.sejapoe.routing.KspRouting
import ru.sejapoe.routing.Route
import kotlin.test.assertEquals

@Route("/test")
object TestRouting {
    @Get("/check")
    fun getCheck() = "nice"

    @Test
    fun `get without params, returning string`(): Unit = testApplication {
        val response = client.get("/test/check")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nice", response.bodyAsText())
    }

    @Test
    fun `get unexpected route`(): Unit = testApplication {
        val response = client.get("/test/unknown")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Get("/error")
    fun getError() { throw NotFoundException() }

    @Test
    fun `get with exception`(): Unit = testApplication {
        val response = client.get("/test/error")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

@KtorDsl
fun testApplication(foo: @KtorDsl suspend ApplicationTestBuilder.() -> Unit) {
    io.ktor.server.testing.testApplication {
        install(KspRouting)
        foo()
    }
}
