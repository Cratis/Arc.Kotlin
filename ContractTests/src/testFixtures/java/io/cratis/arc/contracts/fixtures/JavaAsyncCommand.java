// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.Authorize;
import io.cratis.arc.authorization.Roles;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Java record command fixture with an asynchronous dependency-injected handler. */
@Command
@Authorize(policy = "java-policy", roles = {"java-operator", "java-admin"}, schemes = {"java-scheme"})
@Roles("java-admin")
public record JavaAsyncCommand(
    @CommandKey @NotBlank(message = "Java command id is required") String commandId,
    @Size(min = 2, max = 12, message = "Java value length is invalid")
    @Pattern(regexp = "^[a-z]+$", message = "Java value must be lowercase") String value
) {
    /** Fetches a value asynchronously for the handler. */
    public CompletionStage<JavaProvidedValue> provide(JavaAsyncDependency dependency) {
        return CompletableFuture.completedFuture(new JavaProvidedValue(dependency.respond(value)));
    }

    /** Handles the command asynchronously. */
    public CompletionStage<String> handle(JavaProvidedValue provided) {
        return CompletableFuture.completedFuture(provided.response());
    }
}
