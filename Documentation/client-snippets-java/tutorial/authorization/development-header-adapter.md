```text
Java does not support this workflow yet: the ASP.NET Core adapter is .NET-only. On the JVM, Arc.Kotlin's optional platform identity bridge reads the same x-ms-client-principal headers, but requires cratis.arc.platform-identity.enabled=true and an ArcPlatformIdentityTrust bean that verifies your ingress strips caller-supplied headers. There is no safe generic trust bean to copy: see /arc/backend/kotlin/guides/security/. For another local identity mechanism, register io.cratis.arc.authentication.AsyncAuthenticationHandler.
```
