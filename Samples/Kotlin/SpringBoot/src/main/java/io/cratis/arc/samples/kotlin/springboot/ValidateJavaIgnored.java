// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot;

@io.cratis.arc.artifacts.Command
@io.cratis.arc.authorization.AllowAnonymous
public record ValidateJavaIgnored(JavaIgnoredInput input) {
    public String handle() { return "accepted"; }
}
