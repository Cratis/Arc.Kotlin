// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useState } from 'react';
import { All } from '../generated/features/queryshowcase/All';
import { ById } from '../generated/features/queryshowcase/ById';
import { GetAll } from '../generated/features/queryshowcase/GetAll';
import { Latest } from '../generated/features/queryshowcase/Latest';
import { Columns, Section, formatTime } from './shared';

/**
 * All four query shapes, from one read model.
 *
 * | Kind       | Returns    | Method                   |
 * |------------|------------|--------------------------|
 * | Observable | Single     | `ShowcaseItem.latest()`  |
 * | Observable | Collection | `ShowcaseItem.all()`     |
 * | One-shot   | Single     | `ShowcaseItem.byId(id)`  |
 * | One-shot   | Collection | `ShowcaseItem.getAll()`  |
 *
 * The bottom row is the part worth watching: three independent components each call `All.use()`,
 * and they share one subscription through the query instance cache. Three panels, one connection,
 * one push.
 */
export const QueryShowcasePage = () => {
    const [id, setId] = useState(1);

    return (
        <div>
            <h2>Query Showcase</h2>
            <p>
                Nothing on the read model declares a transport. The return type decides: a{' '}
                <code>Flow</code> becomes a subscription, anything else becomes a one-shot GET.
            </p>

            <Columns>
                <Section title="1 · Observable — single item">
                    <p>
                        <code>ShowcaseItem.latest()</code>, updated every three seconds.
                    </p>
                    <LatestView />
                </Section>

                <Section title="2 · Observable — collection">
                    <p>
                        <code>ShowcaseItem.all()</code>, updated every three seconds.
                    </p>
                    <AllView />
                </Section>

                <Section title="3 · One-shot — single item">
                    <p>
                        <code>ShowcaseItem.byId(id)</code>, re-fetched when the argument changes.
                    </p>
                    <div style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'center' }}>
                        <label htmlFor="showcase-id">id:</label>
                        <input
                            id="showcase-id"
                            type="number"
                            value={id}
                            min={1}
                            onChange={event => setId(Number(event.target.value))}
                            style={{ width: 60 }}
                        />
                    </div>
                    <ByIdView id={id} />
                </Section>

                <Section title="4 · One-shot — collection">
                    <p>
                        <code>ShowcaseItem.getAll()</code>, a fixed snapshot.
                    </p>
                    <GetAllView />
                </Section>
            </Columns>

            <h3 style={{ marginTop: 32 }}>One subscription, three consumers</h3>
            <p>
                Each panel calls <code>All.use()</code> on its own. The query instance cache gives them one
                shared subscription, so every server push updates all three at the same moment.
            </p>
            <Columns columns={3}>
                <Section title="Panel A">
                    <AllView />
                </Section>
                <Section title="Panel B">
                    <AllView />
                </Section>
                <Section title="Panel C">
                    <AllView />
                </Section>
            </Columns>
        </div>
    );
};

const LatestView = () => {
    const [result] = Latest.use();
    if (result.isPerforming) return <p>Connecting…</p>;
    if (!result.hasData) return <p>No data yet.</p>;
    return <ItemCard id={result.data.id} name={result.data.name} updatedAt={result.data.updatedAt} />;
};

const AllView = () => {
    const [result] = All.use();
    if (result.isPerforming) return <p>Connecting…</p>;
    if (!result.hasData) return <p>No data yet.</p>;
    return <ItemList items={result.data ?? []} />;
};

const ByIdView = ({ id }: { id: number }) => {
    const [result] = ById.use({ id });
    if (result.isPerforming) return <p>Loading…</p>;
    if (!result.hasData) return <p>No data.</p>;
    return <ItemCard id={result.data.id} name={result.data.name} updatedAt={result.data.updatedAt} />;
};

const GetAllView = () => {
    const [result] = GetAll.use();
    if (result.isPerforming) return <p>Loading…</p>;
    if (!result.hasData) return <p>No data.</p>;
    return <ItemList items={result.data ?? []} />;
};

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
        <span style={{ color: '#888', fontSize: 11, marginRight: 6 }}>#{id ?? '?'}</span>
        <strong>{name ?? '…'}</strong>
        <span style={{ color: '#aaa', fontSize: 11, marginLeft: 8 }}>{formatTime(updatedAt)}</span>
    </div>
);
