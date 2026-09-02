# Arc OpenAPI Spring Boot Starter

`io.cratis:arc-openapi-spring-boot-starter` generates an OpenAPI 3.1 document from generated `ArcArtifactModule` metadata. It is independent of springdoc and uses `swagger-models` as its public document model.

The auto-configuration activates for servlet web applications with Arc modules, builds and serializes one deterministic document at startup, and serves the cached bytes at `/v3/api-docs` and `/.cratis/openapi.json`. Application-provided `OpenAPI` or `ArcOpenApiDocument` beans disable generation, and application request mappings take precedence over the fallback document routes.

See [Publish an OpenAPI document](../../Documentation/guides/openapi.md) for usage and the generated contract.
