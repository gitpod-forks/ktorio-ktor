/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import kotlin.test.*

@Suppress("DEPRECATION")
class CallValidatorTest {
    private var firstHandler = 0
    private var secondHandler = 0
    private var handleTriggered = false
    private var validator = 0

    @Test
    fun testAllExceptionHandlers() = testWithEngine(MockEngine) {
        var thirdHandler = 0

        config {
            engine {
                addHandler { respondOk() }
            }
            HttpResponseValidator {
                handleResponseException {
                    firstHandler++
                    assertTrue(it is CallValidatorTestException)
                }

                handleResponseException {
                    secondHandler++
                    assertTrue(it is CallValidatorTestException)
                }

                handleResponseExceptionWithRequest { cause, request ->
                    thirdHandler++
                    assertTrue(cause is CallValidatorTestException)
                    assertNotNull(request)
                }
            }
        }

        test { client ->
            client.responsePipeline.intercept(HttpResponsePipeline.Transform) { throw CallValidatorTestException() }

            var fourthHandler = false
            try {
                client.get {}.body<String>()
            } catch (_: CallValidatorTestException) {
                fourthHandler = true
            }

            assertEquals(1, firstHandler)
            assertEquals(1, secondHandler)
            assertEquals(1, thirdHandler)
            assertTrue(fourthHandler)
        }
    }

    @Test
    fun testExceptionFromEngine() = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler { throw CallValidatorTestException() }
            }
            HttpResponseValidator {
                handleResponseException {
                    assertTrue(it is CallValidatorTestException)
                    firstHandler++
                }
                handleResponseExceptionWithRequest { cause, request ->
                    assertTrue(cause is CallValidatorTestException)
                    assertNotNull(request)
                    secondHandler++
                }
            }
        }
        test { client ->
            try {
                client.request()
            } catch (_: CallValidatorTestException) {
            }

            assertEquals(1, firstHandler)
            assertEquals(1, secondHandler)
        }
    }

    @Test
    fun testExceptionFromResponsePipeline() = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler { respondOk() }
            }
            HttpResponseValidator {
                handleResponseException {
                    assertTrue(it is CallValidatorTestException)
                    handleTriggered = true
                }
            }
        }
        test { client ->
            client.responsePipeline.intercept(HttpResponsePipeline.Transform) { throw CallValidatorTestException() }
            try {
                client.get {}.body<String>()
            } catch (_: CallValidatorTestException) {
            }

            assertTrue(handleTriggered)
        }
    }

    @Test
    fun testExceptionFromReceivePipeline() = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler { respondOk() }
            }
            HttpResponseValidator {
                handleResponseException {
                    assertTrue(it is CallValidatorTestException)
                    handleTriggered = true
                }
            }
        }
        test { client ->
            client.receivePipeline.intercept(HttpReceivePipeline.State) { throw CallValidatorTestException() }
            try {
                client.get {}.body<String>()
            } catch (_: CallValidatorTestException) {
            }

            assertTrue(handleTriggered)
        }
    }

    @Test
    fun testMergeMultipleConfigs() = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler { respondOk() }
            }
            HttpResponseValidator {
                handleResponseException {
                    firstHandler++
                    assertTrue(it is CallValidatorTestException)
                }
            }

            HttpResponseValidator {
                handleResponseException {
                    secondHandler++
                    assertTrue(it is CallValidatorTestException)
                }
            }
        }

        test { client ->
            client.responsePipeline.intercept(HttpResponsePipeline.Transform) { throw CallValidatorTestException() }

            try {
                client.get {}.body<String>()
            } catch (_: CallValidatorTestException) {
                handleTriggered = true
            }

            assertEquals(1, firstHandler)
            assertEquals(1, secondHandler)
            assertTrue(handleTriggered)
        }
    }

    @Test
    fun testResponseValidation() = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler {
                    val status = HttpStatusCode(42, "Awesome code")
                    respond("Awesome response", status)
                }
            }

            HttpResponseValidator {
                validateResponse {
                    assertEquals(42, it.status.value)
                    validator++
                }
            }
        }

        test { client ->
            val response = client.get {}.body<String>()
            assertEquals("Awesome response", response)
            assertEquals(1, validator)
        }
    }

    @Test
    fun testResponseValidationOnHttpResponse() = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler {
                    val status = HttpStatusCode(42, "Awesome code")
                    respond("Awesome response", status)
                }
            }

            HttpResponseValidator {
                validateResponse {
                    assertEquals(42, it.status.value)
                    validator++
                }
            }
        }

        test { client ->
            client.get {}
            assertEquals(1, validator)
        }
    }

    @Test
    fun testResponseValidationThrowsResponseException() = testWithEngine(MockEngine) {
        config {
            expectSuccess = true
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
        }

        test { client ->
            try {
                client.get {}
                fail("Should fail")
            } catch (cause: ResponseException) {
                assertEquals(900, cause.response.status.value)
                assertEquals("Awesome response", cause.response.body())
            }
        }
    }

    @Test
    fun testResponseValidationThrowsResponseExceptionOnReceive() = testWithEngine(MockEngine) {
        config {
            expectSuccess = true
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
        }

        test { client ->
            try {
                client.get {}.body<String>()
                fail("Should fail")
            } catch (cause: ResponseException) {
                assertEquals(900, cause.response.status.value)
                assertEquals("Awesome response", cause.response.body())
            }
        }
    }

    @Test
    fun testResponseValidationPerRequestConfigFromFalseToTrue() = testWithEngine(MockEngine) {
        config {
            expectSuccess = false
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
        }

        test { client ->
            // expectSuccess default
            val response = client.get {}
            assertEquals(900, response.status.value)

            // expectSuccess overwritten
            try {
                client.get {
                    expectSuccess = true
                }
                fail("Should fail")
            } catch (cause: ResponseException) {
                assertEquals(900, cause.response.status.value)
                assertEquals("Awesome response", cause.response.body())
            }
        }
    }

    @Test
    fun testResponseValidationPerRequestConfigFromTrueToFalse() = testWithEngine(MockEngine) {
        config {
            expectSuccess = true
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
        }

        test { client ->
            // expectSuccess overwritten
            val response = client.get {
                expectSuccess = false
            }
            assertEquals(900, response.status.value)

            // expectSuccess default
            try {
                client.get {}
                fail("Should fail")
            } catch (cause: ResponseException) {
                assertEquals(900, cause.response.status.value)
                assertEquals("Awesome response", cause.response.body())
            }
        }
    }

    @Test
    fun testCustomResponseValidationRunsAfterDefault() = testWithEngine(MockEngine) {
        config {
            expectSuccess = true
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
            HttpResponseValidator {
                validateResponse {
                    throw IllegalStateException("Should not throw")
                }
            }
        }

        test { client ->
            try {
                client.get {}
                fail("Should fail")
            } catch (cause: ResponseException) {
                assertEquals(900, cause.response.status.value)
                assertEquals("Awesome response", cause.response.body())
            }
        }
    }

    @Test
    fun testCustomResponseValidationWithoutDefault() = testWithEngine(MockEngine) {
        config {
            expectSuccess = false
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
            HttpResponseValidator {
                validateResponse {
                    throw IllegalStateException("My custom error")
                }
            }
        }

        test { client ->
            try {
                client.get {}
                fail("Should fail")
            } catch (cause: IllegalStateException) {
                assertEquals("My custom error", cause.message)
            }
        }
    }

    @Test
    fun testCustomResponseValidationWithoutDefaultPerRequestLevel() = testWithEngine(MockEngine) {
        config {
            expectSuccess = true
            engine {
                addHandler {
                    val status = HttpStatusCode(900, "Awesome code")
                    respond("Awesome response", status)
                }
            }
            HttpResponseValidator {
                validateResponse {
                    throw IllegalStateException("My custom error")
                }
            }
        }

        test { client ->
            try {
                client.get { expectSuccess = false }
                fail("Should fail")
            } catch (cause: IllegalStateException) {
                assertEquals("My custom error", cause.message)
            }
        }
    }

    @Test
    fun testThrowsOriginalExceptionWhenBodyIsNotSerialized() = testWithEngine(MockEngine) {
        class TestException : RuntimeException()

        config {
            engine {
                addHandler { respond("OK") }
            }
        }

        test { client ->
            client.requestPipeline.intercept(HttpRequestPipeline.Render) {
                throw TestException()
            }

            assertFailsWith<TestException> {
                client.get("/") {
                    setBody(listOf("a", "b", "c"))
                }
            }
        }
    }

    @Test
    fun testCanNotAccessBodyAndCallWhenNotSerialized() = testWithEngine(MockEngine) {
        class TestException : RuntimeException()

        config {
            engine {
                addHandler { respond("OK") }
            }
            HttpResponseValidator {
                handleResponseExceptionWithRequest { _, request ->
                    assertFailsWith<IllegalStateException> { request.content }
                    assertFailsWith<IllegalStateException> { request.call }
                }
            }
        }

        test { client ->
            client.requestPipeline.intercept(HttpRequestPipeline.Render) {
                throw TestException()
            }

            assertFailsWith<TestException> {
                client.get("/") {
                    setBody(listOf("a", "b", "c"))
                }
            }
        }
    }
}

internal class CallValidatorTestException : Throwable()
