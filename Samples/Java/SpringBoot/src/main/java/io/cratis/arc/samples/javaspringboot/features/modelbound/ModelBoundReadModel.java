// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.modelbound;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The smallest read model Arc can host.
 *
 * @param data The numeric payload.
 */
@ReadModel
@AllowAnonymous
public record ModelBoundReadModel(int data) {
    /**
     * Gets every item.
     *
     * @return Every item.
     */
    public static List<ModelBoundReadModel> getAll() {
        return IntStream.rangeClosed(1, 20).mapToObj(ModelBoundReadModel::new).toList();
    }

    /**
     * Gets one item by identifier.
     *
     * @param id The identifier to parse.
     * @return The matching item, or zero when the identifier is not numeric.
     */
    public static ModelBoundReadModel getById(String id) {
        try {
            return new ModelBoundReadModel(Integer.parseInt(id));
        } catch (NumberFormatException ignored) {
            return new ModelBoundReadModel(0);
        }
    }
}
