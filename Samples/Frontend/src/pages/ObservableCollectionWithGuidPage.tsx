// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { Guid } from '@cratis/fundamentals';
import { useState } from 'react';
import { AddObservableCollectionWithGuidItem } from '../generated/features/observablecollectionwithguid/AddObservableCollectionWithGuidItem';
import { All } from '../generated/features/observablecollectionwithguid/All';
import { RemoveObservableCollectionWithGuidItem } from '../generated/features/observablecollectionwithguid/RemoveObservableCollectionWithGuidItem';
import { Section, tableStyles } from './shared';

/**
 * The same collection, keyed by a UUID.
 *
 * A JVM `UUID` does not reach the browser as a bare string: the generated client hydrates it as a
 * `Guid` from `@cratis/fundamentals`. That matters for change tracking — set identity is computed
 * from the key, so the conversion has to survive the round trip intact.
 */
export const ObservableCollectionWithGuidPage = () => {
    const [result] = All.use();
    const [addCommand, setAddValues] = AddObservableCollectionWithGuidItem.use();
    const [removeCommand, setRemoveValues] = RemoveObservableCollectionWithGuidItem.use();
    const [label, setLabel] = useState('');

    const items = result.data ?? [];

    const add = async () => {
        setAddValues({ id: Guid.create(), label });
        await addCommand.execute();
        setLabel('');
    };

    const remove = async (id: Guid) => {
        setRemoveValues({ id });
        await removeCommand.execute();
    };

    return (
        <div>
            <h2>Observable Collection (UUID)</h2>
            <p>
                Backed by <code>ObservableCollectionWithGuidItem.all()</code>, whose identifier is a JVM{' '}
                <code>UUID</code> and a client <code>Guid</code>.
            </p>

            <Section title="Add item">
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                    <input
                        value={label}
                        placeholder="Label"
                        onChange={event => setLabel(event.target.value)}
                        style={{ width: 180 }}
                    />
                    <button onClick={add} disabled={!label.trim()}>
                        Add with a new Guid
                    </button>
                </div>
            </Section>

            <div style={{ height: 16 }} />

            <Section title="Current collection">
                {result.isPerforming && <p>Connecting…</p>}
                {!result.isPerforming && items.length === 0 && <p>No items in the collection.</p>}
                {items.length > 0 && (
                    <table style={tableStyles.table}>
                        <thead>
                            <tr style={tableStyles.headerRow}>
                                <th style={tableStyles.headerCell}>ID</th>
                                <th style={tableStyles.headerCell}>Label</th>
                                <th style={{ ...tableStyles.headerCell, textAlign: 'right' }}>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            {items.map(item => (
                                <tr key={item.id.toString()} style={tableStyles.row}>
                                    <td style={{ ...tableStyles.cell, fontFamily: 'monospace', fontSize: 12 }}>
                                        {item.id.toString()}
                                    </td>
                                    <td style={tableStyles.cell}>{item.label}</td>
                                    <td style={tableStyles.actions}>
                                        <button onClick={() => remove(item.id)}>Remove</button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </Section>
        </div>
    );
};
