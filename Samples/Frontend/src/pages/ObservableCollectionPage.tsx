// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useMemo } from 'react';
import { AddObservableCollectionItem } from '../generated/features/observablecollection/AddObservableCollectionItem';
import { All } from '../generated/features/observablecollection/All';
import { RemoveObservableCollectionItem } from '../generated/features/observablecollection/RemoveObservableCollectionItem';
import { Section, tableStyles } from './shared';

/**
 * Commands mutate; a subscription reports.
 *
 * Neither command returns the new collection, and this page never refetches. The list below stays
 * current because the read model pushed — which is what separates an observable query from a
 * cleverly timed poll.
 */
export const ObservableCollectionPage = () => {
    const [result] = All.use();
    const [addCommand, setAddValues] = AddObservableCollectionItem.use({ id: 3, label: '' });
    const [removeCommand, setRemoveValues] = RemoveObservableCollectionItem.use();

    const items = result.data ?? [];
    const nextId = useMemo(
        () => (items.length > 0 ? Math.max(...items.map(item => item.id)) + 1 : 1),
        [items]
    );

    const add = async () => {
        await addCommand.execute();
        setAddValues({ id: nextId + 1, label: '' });
    };

    const remove = async (id: number) => {
        setRemoveValues({ id });
        await removeCommand.execute();
    };

    return (
        <div>
            <h2>Observable Collection</h2>
            <p>
                An integer-keyed collection behind <code>ObservableCollectionItem.all()</code>, mutated by
                two commands.
            </p>

            <Section title="Add item">
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                    <input
                        type="number"
                        value={addCommand.id ?? nextId}
                        onChange={event => setAddValues({ id: Number(event.target.value) })}
                        style={{ width: 80 }}
                    />
                    <input
                        value={addCommand.label ?? ''}
                        placeholder="Label"
                        onChange={event => setAddValues({ label: event.target.value })}
                        style={{ width: 180 }}
                    />
                    <button onClick={add} disabled={!addCommand.label?.trim()}>
                        Add
                    </button>
                </div>
            </Section>

            <div style={{ height: 16 }} />

            <Section title="Current collection">
                <p style={{ color: '#666', fontSize: 13 }}>
                    Item count: <strong>{items.length}</strong>
                </p>
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
                                <tr key={item.id} style={tableStyles.row}>
                                    <td style={tableStyles.cell}>{item.id}</td>
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
