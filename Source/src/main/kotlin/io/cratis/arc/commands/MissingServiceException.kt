// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Thrown when a generated artifact requires a service that the execution resolver cannot provide. */
public class MissingServiceException(public val serviceType: Class<*>) :
    IllegalStateException("No service is registered for type '${serviceType.name}'.")
