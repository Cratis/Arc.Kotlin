// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Marker for filters that must run before every ordinary command filter. */
public interface AuthorizationCommandFilter : CommandFilter
