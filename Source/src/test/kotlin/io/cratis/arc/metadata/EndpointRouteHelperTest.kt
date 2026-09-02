// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EndpointRouteHelperTest {
    @Test
    fun `defaults match Arc endpoint conventions`() {
        val options = ApiEndpointOptions()
        assertEquals("api", options.routePrefix)
        assertEquals(0, options.segmentsToSkipForRoute)
        assertEquals(true, options.includeCommandNameInRoute)
        assertEquals(true, options.includeQueryNameInRoute)
        assertEquals(true, options.enableQueryHttpMethod)
    }

    @Test
    fun `command route uses package and dotnet compatible kebab casing`() {
        val descriptor = CommandDescriptor(
            "AddAuthor",
            "MyApp.Features.Authors.AddAuthor",
            location = listOf("MyApp", "Features", "Authors")
        )
        assertEquals(
            "/api/my-app/features/authors/add-author",
            EndpointRouteHelper.commandRoute(descriptor)
        )
    }

    @Test
    fun `namespace conflict forces endpoint name`() {
        val options = ApiEndpointOptions(includeCommandNameInRoute = false)
        val descriptor = CommandDescriptor(
            "AddAuthor",
            "MyApp.Authors.AddAuthor",
            location = listOf("MyApp", "Authors")
        )
        assertEquals("/api/my-app/authors", EndpointRouteHelper.commandRoute(descriptor, options))
        assertEquals("/api/my-app/authors/add-author", EndpointRouteHelper.commandRoute(descriptor, options, true))
    }

    @Test
    fun `skipped package segments and empty prefix are supported`() {
        val options = ApiEndpointOptions("", 2)
        val descriptor = CommandDescriptor(
            "RunNow",
            "Company.Product.Tools.RunNow",
            location = listOf("Company", "Product", "Tools")
        )
        assertEquals("/tools/run-now", EndpointRouteHelper.commandRoute(descriptor, options))
    }

    @Test
    fun `underscores acronyms and repeated prefix slashes match dotnet`() {
        val options = ApiEndpointOptions("//API__V1///")
        val descriptor = CommandDescriptor(
            "GetURL_Value",
            "MyHTTP_App.GetURL_Value",
            location = listOf("MyHTTP_App")
        )
        assertEquals(
            "/api__v1/my-h-t-t-p-app/get-u-r-l-value",
            EndpointRouteHelper.commandRoute(descriptor, options)
        )
        assertEquals("h-t-t-p-status", EndpointRouteHelper.toKebabCase("HTTPStatus"))
    }

    @Test
    fun `query explicit path is returned verbatim`() {
        val descriptor = QueryDescriptor(
            "custom",
            "MyApp.Queries",
            "kotlin.String",
            explicitPath = "/MixedCase/Do_NotNormalize"
        )
        assertEquals("/MixedCase/Do_NotNormalize", EndpointRouteHelper.queryRoute(descriptor))
    }
}
