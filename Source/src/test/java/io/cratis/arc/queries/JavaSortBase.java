// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries;

/** Java declaration inherited by the Kotlin sorting-access fixture. */
public class JavaSortBase {
    public final String name;
    public JavaSortBase(String name) { this.name = name; }
    public String getName() { return new StringBuilder(name).reverse().toString(); }
}
