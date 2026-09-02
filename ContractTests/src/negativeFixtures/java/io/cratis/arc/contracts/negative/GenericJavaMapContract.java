// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import java.util.Map;

/** Invalid interface map property whose value is a method type parameter. */
public interface GenericJavaMapContract {
    <T> Map<String, T> values();
}
