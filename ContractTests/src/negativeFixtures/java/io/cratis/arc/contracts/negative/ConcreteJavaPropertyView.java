// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

public interface ConcreteJavaPropertyView {
    ConcreteJavaPropertyBase getValue();
    java.util.List<ConcreteJavaPropertyBase> values();
}
