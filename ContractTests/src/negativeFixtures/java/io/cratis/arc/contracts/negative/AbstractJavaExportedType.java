// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ExportedType;

/** Java expresses the same unsupported export target, so the diagnostic must reach Java authors too. */
@ExportedType
public abstract class AbstractJavaExportedType {
    public abstract String name();
}
