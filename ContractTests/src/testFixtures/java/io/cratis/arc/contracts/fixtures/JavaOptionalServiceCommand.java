// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import java.util.Optional;

/** Java command composing provided values and ordinary services through Optional. */
@Command
public record JavaOptionalServiceCommand(String source) {
    /** Supplies the dependency only for the explicit provided-value case. */
    public JavaOptionalServiceDependency provide() {
        return source.equals("provided") ? new JavaOptionalServiceDependency("provided") : null;
    }

    /** Uses a provided dependency before falling back to an ordinary service. */
    public String handle(Optional<JavaOptionalServiceDependency> dependency) {
        return dependency.map(JavaOptionalServiceDependency::value).orElse("empty");
    }
}
