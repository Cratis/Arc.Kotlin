// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

/**
 * Identity details exposed by the Java sample.
 *
 * No command or query references this type. KSP discovers it from the declared type argument of
 * {@link SampleIdentityDetailsProvider}, so no explicit export annotation is needed and the
 * generated client still gets `SampleIdentityDetails.ts`.
 *
 * @param source Names the application that produced the details.
 */
public record SampleIdentityDetails(String source) {
}
