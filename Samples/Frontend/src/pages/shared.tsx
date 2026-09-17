// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import type { CSSProperties, ReactNode } from 'react';

/** A bordered panel with a heading. */
export const Section = ({ title, children }: { title: string; children: ReactNode }) => (
    <div style={{ border: '1px solid #ddd', borderRadius: 6, padding: 16 }}>
        <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{title}</h3>
        {children}
    </div>
);

/** A two-column layout for the panels a page compares side by side. */
export const Columns = ({ children, columns = 2 }: { children: ReactNode; columns?: number }) => (
    <div style={{ display: 'grid', gridTemplateColumns: `repeat(${columns}, 1fr)`, gap: 24 }}>{children}</div>
);

/** A labeled checkbox. */
export const Toggle = ({
    label,
    enabled,
    onChange
}: {
    label: string;
    enabled: boolean;
    onChange: (next: boolean) => void;
}) => (
    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', userSelect: 'none' }}>
        <input type="checkbox" checked={enabled} onChange={event => onChange(event.target.checked)} />
        {label}
    </label>
);

/** Says out loud whether a conditional query is running, so "nothing happened" is never ambiguous. */
export const Status = ({ enabled }: { enabled: boolean }) => (
    <div style={{ fontSize: 12, marginBottom: 8, color: enabled ? '#2a7c2a' : '#999', fontStyle: 'italic' }}>
        {enabled ? '● active — query or subscription running' : '○ inactive — no request sent'}
    </div>
);

/** Formats a time the server sent, tolerating a value that never arrived. */
export const formatTime = (value: Date | string | undefined | null): string => {
    if (value === undefined || value === null) {
        return '—';
    }
    const date = new Date(value as string);
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleTimeString();
};

/** Shared table styling used by the collection pages. */
export const tableStyles: Record<string, CSSProperties> = {
    table: { width: '100%', borderCollapse: 'collapse', fontSize: 13 },
    headerRow: { background: '#f0f0f0' },
    headerCell: { padding: '6px 8px', textAlign: 'left' },
    row: { borderBottom: '1px solid #eee' },
    cell: { padding: '6px 8px' },
    actions: { padding: '6px 8px', textAlign: 'right' }
};
