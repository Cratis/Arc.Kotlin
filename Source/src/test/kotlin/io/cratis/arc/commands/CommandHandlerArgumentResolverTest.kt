// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.ArcPrincipal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class CommandHandlerArgumentResolverTest {
    private val values: List<Any> = listOf(true, 1.toByte(), 'a', 2.toShort(), 3, 4L, 5.0f, 6.0)

    @Test
    fun `boxed scalar tokens resolve the original provided instances`() = runBlocking {
        val resolver = resolver(values)

        values.forEach { value ->
            assertSame(value, resolver.resolve(value.javaClass, "handle", "value"))
        }
    }

    @Test
    fun `primitive tokens retain the existing class cast rejection after matching provided values`() {
        val primitiveTypes = listOf(
            Boolean::class.java, Byte::class.java, Char::class.java, Short::class.java,
            Int::class.java, Long::class.java, Float::class.java, Double::class.java
        )

        primitiveTypes.zip(values).forEach { (type, value) ->
            assertThrows(ClassCastException::class.java) {
                runBlocking { resolver(listOf(value)).resolve(type, "handle", "value") }
            }
        }
    }

    @Test
    fun `reference tokens consume provided instances in order`() = runBlocking {
        val first = Any()
        val second = Any()
        val resolver = resolver(listOf(first, second))

        assertSame(first, resolver.resolve(Any::class.java, "handle", "first"))
        assertSame(second, resolver.resolve(Any::class.java, "handle", "second"))
    }

    private fun resolver(values: List<Any>): CommandHandlerArgumentResolver = CommandHandlerArgumentResolver(
        CommandContext(
            UUID.randomUUID(),
            "command",
            String::class.java,
            ArcPrincipal.anonymous(),
            serviceResolver = object : ServiceResolver {
                override fun <T : Any> resolve(type: Class<T>): T? = null
            },
            providedValues = values
        )
    )
}
