```kotlin
@Bean
fun applicationChain(http: HttpSecurity, filter: ArcPlatformAuthenticationFilter): SecurityFilterChain {
    http.addFilterBefore(filter, AnonymousAuthenticationFilter::class.java)
    http.authorizeHttpRequests { rules ->
        rules.requestMatchers("/.cratis/commands").permitAll().anyRequest().authenticated()
    }
    http.exceptionHandling { errors ->
        errors.authenticationEntryPoint { _, response, _ -> response.status = 401 }
    }
    return http.build()
}
```
