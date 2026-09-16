// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.validation.IgnoreValidation;

public record JavaIgnoreContractInput(@IgnoreValidation String ignored,
    @IgnoreValidation JavaFluentContractInput ignoredChild,
    @IgnoreValidation java.util.List<JavaFluentContractInput> ignoredList,
    JavaFluentContractInput validated, String sibling) { }
