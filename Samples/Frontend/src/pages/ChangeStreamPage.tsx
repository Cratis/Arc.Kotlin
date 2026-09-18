// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import type { CSSProperties } from 'react';
import { useEffect, useState } from 'react';
import { AddChangeStreamItem } from '../generated/features/changestream/AddChangeStreamItem';
import { All } from '../generated/features/changestream/All';
import type { ChangeStreamItem } from '../generated/features/changestream/ChangeStreamItem';
import { RemoveChangeStreamItem } from '../generated/features/changestream/RemoveChangeStreamItem';
import { UpdateChangeStreamItem } from '../generated/features/changestream/UpdateChangeStreamItem';
import { tableStyles } from './shared';

interface ChangeLogEntry {
    added: ChangeStreamItem[];
    replaced: ChangeStreamItem[];
    removed: ChangeStreamItem[];
    timestamp: Date;
}

const entryColor = { added: '#d4edda', replaced: '#fff3cd', removed: '#f8d7da' } as const;

const pill = (color: string): CSSProperties => ({
    background: color,
    borderRadius: 4,
    padding: '1px 6px',
    fontSize: 12,
    display: 'inline-block',
    marginRight: 4
});

/**
 * What actually travels when a collection changes.
 *
 * Mutate one row and the log on the right shows one entry. Switch the toolbar's transfer mode to
 * **Full** and the same mutation reports the whole collection as added — same data on screen,
 * very different amount on the wire. That is the entire argument for Delta, made visible.
 */
export const ChangeStreamPage = () => {
    const [allResult] = All.use();
    const changes = All.useChangeStream(item => item.id);
    const [changeLog, setChangeLog] = useState<ChangeLogEntry[]>([]);

    const [addCommand, setAddValues] = AddChangeStreamItem.use();
    const [updateCommand, setUpdateValues] = UpdateChangeStreamItem.use();
    const [removeCommand, setRemoveValues] = RemoveChangeStreamItem.use();

    const items = allResult.data ?? [];
    const nextId = items.length > 0 ? Math.max(...items.map(item => item.id)) + 1 : 1;

    useEffect(() => {
        if (changes.added.length === 0 && changes.replaced.length === 0 && changes.removed.length === 0) {
            return;
        }
        setChangeLog(log => [
            {
                added: changes.added,
                replaced: changes.replaced,
                removed: changes.removed,
                timestamp: new Date()
            },
            ...log.slice(0, 19)
        ]);
    }, [changes]);

    const add = async () => {
        await addCommand.execute();
        setAddValues({ id: nextId + 1, label: '', value: 0 });
    };

    const remove = async (id: number) => {
        setRemoveValues({ id });
        await removeCommand.execute();
    };

    return (
        <div>
            <h2>Change Stream</h2>
            <p>
                <code>All.useChangeStream(item =&gt; item.id)</code> reports what moved between emissions
                rather than handing you the whole collection again.
            </p>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
                <div>
                    <h3>Current collection</h3>
                    {allResult.isPerforming && <p>Connecting…</p>}
                    <table style={tableStyles.table}>
                        <thead>
                            <tr style={tableStyles.headerRow}>
                                <th style={tableStyles.headerCell}>ID</th>
                                <th style={tableStyles.headerCell}>Label</th>
                                <th style={{ ...tableStyles.headerCell, textAlign: 'right' }}>Value</th>
                                <th />
                            </tr>
                        </thead>
                        <tbody>
                            {items.map(item => (
                                <tr key={item.id} style={tableStyles.row}>
                                    <td style={tableStyles.cell}>{item.id}</td>
                                    <td style={tableStyles.cell}>{item.label}</td>
                                    <td style={{ ...tableStyles.cell, textAlign: 'right' }}>{item.value}</td>
                                    <td style={tableStyles.actions}>
                                        <button
                                            style={{ marginRight: 4, fontSize: 12 }}
                                            onClick={() =>
                                                setUpdateValues({
                                                    id: item.id,
                                                    label: item.label,
                                                    value: item.value
                                                })
                                            }>
                                            Edit
                                        </button>
                                        <button
                                            style={{ fontSize: 12, color: 'red' }}
                                            onClick={() => remove(item.id)}>
                                            Remove
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>

                    <h3 style={{ marginTop: 24 }}>Add item</h3>
                    <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                        <input
                            type="number"
                            placeholder="ID"
                            value={addCommand.id ?? nextId}
                            onChange={event => setAddValues({ id: Number(event.target.value) })}
                            style={{ width: 60 }}
                        />
                        <input
                            placeholder="Label"
                            value={addCommand.label ?? ''}
                            onChange={event => setAddValues({ label: event.target.value })}
                            style={{ width: 100 }}
                        />
                        <input
                            type="number"
                            placeholder="Value"
                            value={addCommand.value ?? 0}
                            onChange={event => setAddValues({ value: Number(event.target.value) })}
                            style={{ width: 70 }}
                        />
                        <button onClick={add}>Add</button>
                    </div>

                    <h3 style={{ marginTop: 24 }}>Update item</h3>
                    <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                        <select
                            value={updateCommand.id ?? ''}
                            onChange={event => {
                                const id = Number(event.target.value);
                                const existing = items.find(item => item.id === id);
                                setUpdateValues({
                                    id,
                                    label: existing?.label ?? '',
                                    value: existing?.value ?? 0
                                });
                            }}
                            style={{ width: 80 }}>
                            <option value="">— ID —</option>
                            {items.map(item => (
                                <option key={item.id} value={item.id}>
                                    {item.id}
                                </option>
                            ))}
                        </select>
                        <input
                            placeholder="Label"
                            value={updateCommand.label ?? ''}
                            onChange={event => setUpdateValues({ label: event.target.value })}
                            style={{ width: 100 }}
                        />
                        <input
                            type="number"
                            placeholder="Value"
                            value={updateCommand.value ?? 0}
                            onChange={event => setUpdateValues({ value: Number(event.target.value) })}
                            style={{ width: 70 }}
                        />
                        <button onClick={() => updateCommand.execute()} disabled={!updateCommand.id}>
                            Update
                        </button>
                    </div>
                </div>

                <div>
                    <h3>
                        Change log{' '}
                        <span style={{ fontWeight: 400, fontSize: 13 }}>(last 20 pushes)</span>
                    </h3>
                    <p style={{ fontSize: 12, color: '#666' }}>
                        Each block is one server push. In <strong>Delta</strong> mode only mutated rows
                        appear; in <strong>Full</strong> mode every push reports the entire collection as{' '}
                        <span style={pill(entryColor.added)}>added</span>.
                    </p>
                    {changeLog.length === 0 && (
                        <p style={{ color: '#888' }}>No pushes yet — mutate the collection on the left.</p>
                    )}
                    <div style={{ maxHeight: 480, overflowY: 'auto' }}>
                        {changeLog.map((entry, index) => (
                            <div
                                key={index}
                                style={{
                                    marginBottom: 8,
                                    padding: '6px 10px',
                                    border: '1px solid #ddd',
                                    borderRadius: 4,
                                    fontSize: 12
                                }}>
                                <div style={{ color: '#888', marginBottom: 4 }}>
                                    {entry.timestamp.toLocaleTimeString()}
                                    {' — '}
                                    {entry.added.length > 0 && (
                                        <span style={pill(entryColor.added)}>+{entry.added.length} added</span>
                                    )}
                                    {entry.replaced.length > 0 && (
                                        <span style={pill(entryColor.replaced)}>
                                            ~{entry.replaced.length} replaced
                                        </span>
                                    )}
                                    {entry.removed.length > 0 && (
                                        <span style={pill(entryColor.removed)}>
                                            −{entry.removed.length} removed
                                        </span>
                                    )}
                                </div>
                                {entry.added.map(item => (
                                    <ChangeRow key={`a-${item.id}`} item={item} color={entryColor.added} sign="+" />
                                ))}
                                {entry.replaced.map(item => (
                                    <ChangeRow
                                        key={`r-${item.id}`}
                                        item={item}
                                        color={entryColor.replaced}
                                        sign="~"
                                    />
                                ))}
                                {entry.removed.map(item => (
                                    <ChangeRow
                                        key={`d-${item.id}`}
                                        item={item}
                                        color={entryColor.removed}
                                        sign="−"
                                    />
                                ))}
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};

const ChangeRow = ({ item, color, sign }: { item: ChangeStreamItem; color: string; sign: string }) => (
    <div style={{ background: color, borderRadius: 3, padding: '2px 6px', marginBottom: 2 }}>
        {sign} #{item.id} {item.label} = {item.value}
    </div>
);
