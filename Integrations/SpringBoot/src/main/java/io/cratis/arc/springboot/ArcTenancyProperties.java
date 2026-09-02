// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.tenancy.SubdomainTenantIdResolver;
import io.cratis.arc.tenancy.TenancyOptions;
import io.cratis.arc.tenancy.TenantId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Tenant resolution, precedence, and access settings for the Spring Boot host. */
public final class ArcTenancyProperties {
    private List<TenantResolverStrategy> resolvers = new ArrayList<>(List.of(TenantResolverStrategy.HEADER));
    private boolean required;
    private String headerName;
    private String queryParameterName = TenancyOptions.DEFAULT_QUERY_PARAMETER_NAME;
    private String claimType = TenancyOptions.DEFAULT_CLAIM_TYPE;
    private String baseDomain = "";
    private String fixedTenantId = TenantId.DEVELOPMENT.toString();
    private boolean constrainToAuthenticatedClaims = true;

    /** Gets resolver strategies in complete precedence order. */
    public List<TenantResolverStrategy> getResolvers() {
        return resolvers;
    }

    /** Sets resolver strategies in complete precedence order. */
    public void setResolvers(List<TenantResolverStrategy> value) {
        resolvers = value == null ? null : new ArrayList<>(value);
    }

    /** Gets whether a request without a resolved tenant is rejected. */
    public boolean isRequired() {
        return required;
    }

    /** Sets whether a request without a resolved tenant is rejected. */
    public void setRequired(boolean value) {
        required = value;
    }

    /** Gets the tenant header, or null to use the legacy cratis.arc.tenant-header value. */
    public String getHeaderName() {
        return headerName;
    }

    /** Sets the tenant header. */
    public void setHeaderName(String value) {
        headerName = value;
    }

    /** Gets the exact tenant query parameter name. */
    public String getQueryParameterName() {
        return queryParameterName;
    }

    /** Sets the exact tenant query parameter name. */
    public void setQueryParameterName(String value) {
        queryParameterName = value;
    }

    /** Gets the tenant membership and resolution claim type. */
    public String getClaimType() {
        return claimType;
    }

    /** Sets the tenant membership and resolution claim type. */
    public void setClaimType(String value) {
        claimType = value;
    }

    /** Gets the base domain used by subdomain resolution. */
    public String getBaseDomain() {
        return baseDomain;
    }

    /** Sets the base domain used by subdomain resolution. */
    public void setBaseDomain(String value) {
        baseDomain = value;
    }

    /** Gets the tenant returned by fixed and development resolution. */
    public String getFixedTenantId() {
        return fixedTenantId;
    }

    /** Sets the tenant returned by fixed and development resolution. */
    public void setFixedTenantId(String value) {
        fixedTenantId = value;
    }

    /** Gets whether authenticated tenant claims constrain externally selected tenants. */
    public boolean isConstrainToAuthenticatedClaims() {
        return constrainToAuthenticatedClaims;
    }

    /** Sets whether authenticated tenant claims constrain externally selected tenants. */
    public void setConstrainToAuthenticatedClaims(boolean value) {
        constrainToAuthenticatedClaims = value;
    }

    TenancyOptions toOptions(String legacyHeaderName) {
        validate(legacyHeaderName);
        return new TenancyOptions(
            effectiveHeaderName(legacyHeaderName),
            queryParameterName,
            claimType,
            baseDomain,
            TenantId.of(fixedTenantId));
    }

    void validate(String legacyHeaderName) {
        if (resolvers == null || resolvers.isEmpty()) {
            throw new IllegalArgumentException("cratis.arc.tenancy.resolvers must contain at least one resolver.");
        }
        if (resolvers.stream().anyMatch(java.util.Objects::isNull) ||
            new HashSet<>(resolvers).size() != resolvers.size()) {
            throw new IllegalArgumentException("cratis.arc.tenancy.resolvers cannot contain null or duplicate values.");
        }
        if (uses(TenantResolverStrategy.HEADER) || uses(TenantResolverStrategy.SUBDOMAIN)) {
            requireText(effectiveHeaderName(legacyHeaderName), "cratis.arc.tenancy.header-name");
        }
        if (uses(TenantResolverStrategy.QUERY)) requireText(queryParameterName, "cratis.arc.tenancy.query-parameter-name");
        if (uses(TenantResolverStrategy.CLAIM) || constrainToAuthenticatedClaims) {
            requireText(claimType, "cratis.arc.tenancy.claim-type");
        }
        if (uses(TenantResolverStrategy.FIXED) || uses(TenantResolverStrategy.DEVELOPMENT)) {
            requireText(fixedTenantId, "cratis.arc.tenancy.fixed-tenant-id");
        }
        if (uses(TenantResolverStrategy.SUBDOMAIN)) {
            new SubdomainTenantIdResolver(new TenancyOptions(
                effectiveHeaderName(legacyHeaderName), queryParameterName, claimType, baseDomain, TenantId.of(fixedTenantId)));
        }
    }

    String effectiveHeaderName(String legacyHeaderName) {
        return headerName == null ? legacyHeaderName : headerName;
    }

    private boolean uses(TenantResolverStrategy strategy) {
        return resolvers != null && resolvers.contains(strategy);
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(property + " cannot be blank.");
    }
}
