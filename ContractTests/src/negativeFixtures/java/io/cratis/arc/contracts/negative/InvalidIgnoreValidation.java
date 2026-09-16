// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

public final class InvalidIgnoreValidation {
    @io.cratis.arc.validation.IgnoreValidation
    public void setName(String name) { }

    @io.cratis.arc.validation.IgnoreValidation
    public static String getStaticValue() { return ""; }
}
