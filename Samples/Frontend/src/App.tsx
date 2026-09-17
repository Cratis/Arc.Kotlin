// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useIdentity } from '@cratis/arc.react/identity';
import { AuthenticationQueriesPage } from './pages/AuthenticationQueriesPage';
import { ChangeStreamPage } from './pages/ChangeStreamPage';
import { ConditionalQueriesPage } from './pages/ConditionalQueriesPage';
import { CrossCuttingAuthorizationPage } from './pages/CrossCuttingAuthorizationPage';
import { LiveFeedPage } from './pages/LiveFeedPage';
import { ObservableCollectionPage } from './pages/ObservableCollectionPage';
import { ObservableCollectionWithGuidPage } from './pages/ObservableCollectionWithGuidPage';
import { QueryShowcasePage } from './pages/QueryShowcasePage';
import { TaskBoardPage } from './pages/TaskBoardPage';
import { TickerPage } from './pages/TickerPage';

/** Every page this sample frontend can show. */
export type Page =
    | 'taskboard'
    | 'authenticationqueries'
    | 'crosscuttingauthorization'
    | 'ticker'
    | 'livefeed'
    | 'queryshowcase'
    | 'conditionalqueries'
    | 'changestream'
    | 'observablecollection'
    | 'observablecollectionwithguid';

const pages: { key: Page; label: string }[] = [
    { key: 'taskboard', label: 'Task Board' },
    { key: 'ticker', label: 'Ticker' },
    { key: 'livefeed', label: 'Live Feed' },
    { key: 'queryshowcase', label: 'Query Showcase' },
    { key: 'conditionalqueries', label: 'Conditional Queries' },
    { key: 'changestream', label: 'Change Stream' },
    { key: 'observablecollection', label: 'Observable Collection' },
    { key: 'observablecollectionwithguid', label: 'Observable Collection (UUID)' },
    { key: 'authenticationqueries', label: 'Authentication Queries' },
    { key: 'crosscuttingauthorization', label: 'Cross-Cutting Authorization' }
];

interface AppProps {
    page: Page;
    onPageChange: (page: Page) => void;
}

/**
 * The sample shell.
 *
 * Every page below talks to the backend exclusively through generated proxies. Nothing hand-writes
 * a URL, a request body, or a response type — which is the point of the whole exercise, and also
 * why the same frontend runs unchanged against the Kotlin host and the Java one.
 */
export const App = ({ page, onPageChange }: AppProps) => {
    const identity = useIdentity();

    return (
        <div style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 1100, margin: '0 auto', padding: 24 }}>
            <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <h1 style={{ marginBottom: 4 }}>Arc for Kotlin and Java</h1>
                <span style={{ color: '#666', fontSize: 13 }}>
                    {identity.isSet ? `Signed in as ${identity.name}` : 'Not signed in'}
                </span>
            </header>
            <p style={{ color: '#666', marginTop: 0 }}>
                The same pages the Arc .NET sample shows, served by a Spring Boot host.
            </p>

            <nav style={{ display: 'flex', gap: 8, margin: '20px 0 24px', flexWrap: 'wrap' }}>
                {pages.map(entry => (
                    <button
                        key={entry.key}
                        onClick={() => onPageChange(entry.key)}
                        style={{
                            fontWeight: page === entry.key ? 700 : 400,
                            padding: '6px 10px',
                            cursor: 'pointer'
                        }}>
                        {entry.label}
                    </button>
                ))}
            </nav>

            {page === 'taskboard' && <TaskBoardPage />}
            {page === 'ticker' && <TickerPage />}
            {page === 'livefeed' && <LiveFeedPage />}
            {page === 'queryshowcase' && <QueryShowcasePage />}
            {page === 'conditionalqueries' && <ConditionalQueriesPage />}
            {page === 'changestream' && <ChangeStreamPage />}
            {page === 'observablecollection' && <ObservableCollectionPage />}
            {page === 'observablecollectionwithguid' && <ObservableCollectionWithGuidPage />}
            {page === 'authenticationqueries' && <AuthenticationQueriesPage />}
            {page === 'crosscuttingauthorization' && <CrossCuttingAuthorizationPage />}
        </div>
    );
};
