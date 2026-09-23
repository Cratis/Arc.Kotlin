```java
@Bean
SecurityFilterChain applicationChain(HttpSecurity http, ArcPlatformAuthenticationFilter filter) throws Exception {
    http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
    http.authorizeHttpRequests(rules -> rules.requestMatchers("/.cratis/commands").permitAll()
        .anyRequest().authenticated());
    http.exceptionHandling(errors -> errors.authenticationEntryPoint(
        (request, response, failure) -> response.setStatus(401)));
    return http.build();
}
```
