// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.contracts.fixtures.KotlinIdentityProvider
import io.cratis.arc.contracts.fixtures.identityFactory
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.json.ArcObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

internal class GeneratedIdentityArtifactsTest {
    @Test
    fun `unannotated identity providers and factories share module manifest metadata`(): Unit = runBlocking {
        val mapper = ArcObjectMapper.create()
        val manifest = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/cratis/arc/ContractTests.json"))
            .use { mapper.readValue(it, ArcArtifactManifest::class.java) }
        val module = ContractTestsArcArtifactModule()
        val expected = setOf("KotlinIdentityDetails", "KotlinFactoryIdentityDetails", "JavaIdentityDetails", "JavaFactoryIdentityDetails", "IdentityAddress")
        assertEquals(expected, module.types.filter { it.name in expected }.map { it.name }.toSet())
        assertEquals(mapper.writeValueAsString(module.types), mapper.writeValueAsString(manifest.types))
        assertEquals(7, manifest.formatVersion)
        assertFalse(module.types.any { it.name.endsWith("IdentityProvider") || it.name == "IdentityTemplate" })
        val context = IdentityProviderContext("city", "name", emptyList())
        assertEquals("city", KotlinIdentityProvider().provide(context).details.address.city)
        assertEquals("city", identityFactory().provide(context).details.source)
    }
}
