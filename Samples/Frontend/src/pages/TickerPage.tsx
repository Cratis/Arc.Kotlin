// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { Observe } from '../generated/features/ticker/Observe';
import { formatTime } from './shared';

/**
 * The smallest observable query there is.
 *
 * `Observe.use()` is the whole client. There is no polling loop, no refetch, no cache invalidation
 * to think about — the server pushes, the component re-renders.
 */
export const TickerPage = () => {
    const [result] = Observe.use();

    return (
        <div>
            <h2>Ticker</h2>
            <p>
                Subscribes to <code>Ticker.observe()</code>, which returns a <code>Flow&lt;Ticker&gt;</code> in
                Kotlin and a <code>Flow.Publisher&lt;Ticker&gt;</code> in Java. The counter advances on the
                server once a second and arrives here without the page asking for it.
            </p>
            {result.isPerforming && <p>Connecting…</p>}
            {result.hasData && (
                <div style={{ fontSize: 48, fontWeight: 700, textAlign: 'center', padding: 32 }}>
                    {result.data.count}
                </div>
            )}
            {result.hasData && (
                <p style={{ textAlign: 'center', color: '#888', fontSize: 12 }}>
                    Last updated: {formatTime(result.data.lastUpdated)}
                </p>
            )}
        </div>
    );
};
