// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useState } from 'react';
import { All } from '../generated/features/queryshowcase/All';
import { ById } from '../generated/features/queryshowcase/ById';
import { GetAll } from '../generated/features/queryshowcase/GetAll';
import { Latest } from '../generated/features/queryshowcase/Latest';
import { Columns, Section, Status, Toggle, formatTime } from './shared';

/**
 * Queries you can hold back until the data they need exists.
 *
 * React hooks cannot be called conditionally, which is exactly the problem: a page that needs a
 * selected id has no way to say "not yet". `when(condition)` is that way. While the condition is
 * false no request is sent and no subscription is opened — not a request that is thrown away, none
 * at all.
 */
export const ConditionalQueriesPage = () => {
    const [byIdEnabled, setByIdEnabled] = useState(false);
    const [selectedId, setSelectedId] = useState(1);
    const [getAllEnabled, setGetAllEnabled] = useState(false);
    const [latestEnabled, setLatestEnabled] = useState(false);
    const [allEnabled, setAllEnabled] = useState(false);

    return (
        <div>
            <h2>Conditional Queries</h2>
            <p>
                Toggle a section on to start its query or subscription, and off to stop it. Watch the
                network tab: while a section is off there is nothing there to see.
            </p>

            <Columns>
                <Section title="One-shot, single — ById.when">
                    <p style={{ fontSize: 13, color: '#555' }}>
                        <code>ById.when(enabled).use(&#123; id &#125;)</code>
                    </p>
                    <div style={{ display: 'flex', gap: 16, alignItems: 'center', marginBottom: 12 }}>
                        <label>
                            id:{' '}
                            <input
                                type="number"
                                value={selectedId}
                                min={1}
                                onChange={event => setSelectedId(Number(event.target.value))}
                                style={{ width: 60, marginLeft: 4 }}
                            />
                        </label>
                        <Toggle label="Enable" enabled={byIdEnabled} onChange={setByIdEnabled} />
                    </div>
                    <Status enabled={byIdEnabled} />
                    <ByIdConditional id={selectedId} enabled={byIdEnabled} />
                </Section>

                <Section title="One-shot, collection — GetAll.when">
                    <p style={{ fontSize: 13, color: '#555' }}>
                        <code>GetAll.when(enabled).use()</code>
                    </p>
                    <Toggle label="Enable" enabled={getAllEnabled} onChange={setGetAllEnabled} />
                    <Status enabled={getAllEnabled} />
                    <GetAllConditional enabled={getAllEnabled} />
                </Section>

                <Section title="Observable, single — Latest.when">
                    <p style={{ fontSize: 13, color: '#555' }}>
                        <code>Latest.when(enabled).use()</code>
                    </p>
                    <Toggle label="Enable" enabled={latestEnabled} onChange={setLatestEnabled} />
                    <Status enabled={latestEnabled} />
                    <LatestConditional enabled={latestEnabled} />
                </Section>

                <Section title="Observable, collection — All.when">
                    <p style={{ fontSize: 13, color: '#555' }}>
                        <code>All.when(enabled).use()</code>
                    </p>
                    <Toggle label="Enable" enabled={allEnabled} onChange={setAllEnabled} />
                    <Status enabled={allEnabled} />
                    <AllConditional enabled={allEnabled} />
                </Section>
            </Columns>
        </div>
    );
};

const ByIdConditional = ({ id, enabled }: { id: number; enabled: boolean }) => {
    const [result] = ById.when(enabled).use({ id });
    if (!enabled || !result.hasData) return <Empty />;
    if (result.isPerforming) return <p>Loading…</p>;
    return <ItemCard id={result.data.id} name={result.data.name} updatedAt={result.data.updatedAt} />;
};

const GetAllConditional = ({ enabled }: { enabled: boolean }) => {
    const [result] = GetAll.when(enabled).use();
    if (!enabled || !result.hasData) return <Empty />;
    if (result.isPerforming) return <p>Loading…</p>;
    return <ItemList items={result.data ?? []} />;
};

const LatestConditional = ({ enabled }: { enabled: boolean }) => {
    const [result] = Latest.when(enabled).use();
    if (!enabled || !result.hasData) return <Empty />;
    if (result.isPerforming) return <p>Connecting…</p>;
    return <ItemCard id={result.data.id} name={result.data.name} updatedAt={result.data.updatedAt} />;
};

const AllConditional = ({ enabled }: { enabled: boolean }) => {
    const [result] = All.when(enabled).use();
    if (!enabled || !result.hasData) return <Empty />;
    if (result.isPerforming) return <p>Connecting…</p>;
    return <ItemList items={result.data ?? []} />;
};

const Empty = () => (
    <p style={{ color: '#aaa', fontStyle: 'italic', margin: 0 }}>Nothing to show — the query is disabled.</p>
);

interface ShowcaseRow {
    id: number;
    name: string;
    updatedAt: Date;
}

const ItemList = ({ items }: { items: ShowcaseRow[] }) => (
    <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
        {items.map(item => (
            <li key={item.id} style={{ borderBottom: '1px solid #eee', padding: '4px 0' }}>
                <ItemCard id={item.id} name={item.name} updatedAt={item.updatedAt} />
            </li>
        ))}
    </ul>
);

const ItemCard = ({ id, name, updatedAt }: ShowcaseRow) => (
    <div>
        <span style={{ color: '#888', fontSize: 11, marginRight: 6 }}>#{id}</span>
        <strong>{name}</strong>
        <span style={{ color: '#aaa', fontSize: 11, marginLeft: 8 }}>{formatTime(updatedAt)}</span>
    </div>
);
