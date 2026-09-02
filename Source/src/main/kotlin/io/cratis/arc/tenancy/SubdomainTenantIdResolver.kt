// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import java.net.IDN
import java.util.Locale

/** Thrown when subdomain tenancy is configured without a usable multi-label base domain. */
public class InvalidTenantBaseDomainException(public val baseDomain: String) : IllegalArgumentException(
    "'$baseDomain' cannot be used as the base domain for subdomain tenancy. " +
        "Use a domain with at least two valid DNS labels, such as 'myapp.com', and not an address literal."
)

/**
 * Resolves exactly one tenant label before a configured base domain, then falls back to the configured header.
 */
public class SubdomainTenantIdResolver(
    private val options: TenancyOptions
) : TenantIdResolver {
    private val suffix: String = normalizeBaseDomain(options.baseDomain)

    override fun resolve(context: TenantResolutionContext): TenantId? {
        val normalizedHost = TenantHost.normalize(context.host.orEmpty())
        if (normalizedHost.endsWith(suffix)) {
            val label = normalizedHost.dropLast(suffix.length)
            if (TenantHost.isLabel(label)) return TenantId(label)
        }
        return if (options.headerName.isBlank()) null else context.header(options.headerName)
            ?.takeIf(String::isNotBlank)
            ?.let(::TenantId)
    }

    private fun normalizeBaseDomain(value: String): String {
        val normalized = TenantHost.normalize(value)
        if (normalized.split('.').size < 2 || normalized.split('.').any { !TenantHost.isLabel(it) }) {
            throw InvalidTenantBaseDomainException(value)
        }
        return ".$normalized"
    }
}

private object TenantHost {
    private val labelExpression = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val ipv4Expression = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")

    fun normalize(value: String): String {
        val withoutPort = withoutPort(value.trim())
        if (withoutPort.isEmpty()) return ""
        val prepared = withoutPort.trim('.').lowercase(Locale.ROOT)
        val ascii = try {
            IDN.toASCII(prepared, IDN.USE_STD3_ASCII_RULES).trim('.')
        } catch (_: IllegalArgumentException) {
            return ""
        }
        if (isAddressLiteral(ascii)) return ""
        return ascii
    }

    fun isLabel(value: String): Boolean = labelExpression.matches(value)

    private fun withoutPort(host: String): String {
        if (host.isEmpty()) return host
        if (host[0] == '[') {
            val closingBracket = host.indexOf(']')
            return if (closingBracket < 0) host else host.substring(0, closingBracket + 1)
        }
        val firstColon = host.indexOf(':')
        if (firstColon < 0) return host
        return if (host.indexOf(':', firstColon + 1) < 0) host.substring(0, firstColon) else host
    }

    private fun isAddressLiteral(value: String): Boolean {
        if (value.startsWith('[') || value.contains(':')) return true
        if (!ipv4Expression.matches(value)) return false
        return value.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
