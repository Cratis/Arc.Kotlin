// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArcCamelCaseTest {
    @Test
    fun `all 46 golden names follow the Arc first-character rule`() {
        val goldenRows = listOf(
            "Id" to "id",
            "ID" to "ID",
            "Url" to "url",
            "URL" to "URL",
            "HTTPStatus" to "HTTPStatus",
            "IOStream" to "IOStream",
            "XMLData" to "XMLData",
            "OAuthID" to "OAuthID",
            "alreadyCamel" to "alreadyCamel",
            "A" to "a",
            "a" to "a",
            "Z" to "z",
            "I" to "i",
            "i" to "i",
            "_name" to "_name",
            "_Name" to "_Name",
            "Name_Value" to "name_Value",
            "Value1" to "value1",
            "V1" to "v1",
            "1Value" to "1Value",
            "X509Certificate" to "x509Certificate",
            "IOStreamReader" to "IOStreamReader",
            "ABC" to "ABC",
            "AB" to "AB",
            "ABc" to "ABc",
            "AbC" to "abC",
            "Ab" to "ab",
            "aB" to "aB",
            "IPAddress" to "IPAddress",
            "SSN" to "SSN",
            "HTTP" to "HTTP",
            "OK" to "OK",
            "Http" to "http",
            "IsHTTPEnabled" to "isHTTPEnabled",
            "MyURLValue" to "myURLValue",
            "UserID" to "userID",
            "userId" to "userId",
            "HTMLElement" to "HTMLElement",
            "DBId" to "DBId",
            "EndpointURL" to "endpointURL",
            "Some Value" to "some Value",
            "A B" to "a B",
            " IPhone" to " IPhone",
            "" to "",
            "_" to "_",
            "SHOUTING_CASE" to "SHOUTING_CASE"
        )

        assertEquals(46, goldenRows.size)
        goldenRows.forEach { (input, expected) ->
            assertEquals(expected, ArcCamelCase.convert(input), input)
        }
    }

    @Test
    fun `capital I with dot is preserved like dotnet invariant lowercase`() {
        assertEquals("\u0130stanbul", ArcCamelCase.convert("\u0130stanbul"))
    }

    @Test
    fun `null is preserved`() {
        assertEquals(null, ArcCamelCase.convert(null))
    }
}
