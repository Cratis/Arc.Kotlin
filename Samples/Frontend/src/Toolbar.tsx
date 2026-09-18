// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { ObservableQueryTransferMode } from '@cratis/arc';
import { QueryTransportMethod } from '@cratis/arc/queries';

/** Everything the toolbar can change about how the client talks to Arc. */
export interface Settings {
    transport: QueryTransportMethod;
    connectionCount: number;
    directMode: boolean;
    transferMode: ObservableQueryTransferMode;
    signedIn: boolean;
    userId: string;
    userName: string;
    roles: string;
}

interface ToolbarProps {
    settings: Settings;
    onChange: (settings: Settings) => void;
}

/**
 * The knobs that make Arc's transport behavior observable rather than theoretical.
 *
 * Switch the transport to Server-Sent Events and the same pages keep working. Raise the connection
 * count and subscriptions spread across several multiplexed connections. Turn on direct mode and
 * each subscription opens its own. Change Delta to Full and the change stream page shows the whole
 * collection on every push instead of just what moved.
 */
export const Toolbar = ({ settings, onChange }: ToolbarProps) => {
    const update = (changes: Partial<Settings>) => onChange({ ...settings, ...changes });

    return (
        <div style={styles.bar}>
            <label>
                Transport{' '}
                <select
                    value={settings.transport}
                    onChange={event => update({ transport: event.target.value as QueryTransportMethod })}>
                    <option value={QueryTransportMethod.WebSocket}>WebSocket</option>
                    <option value={QueryTransportMethod.ServerSentEvents}>Server-Sent Events</option>
                </select>
            </label>

            <label style={styles.inline}>
                Connections{' '}
                <button
                    style={styles.stepper}
                    onClick={() => update({ connectionCount: Math.max(1, settings.connectionCount - 1) })}>
                    -
                </button>
                <span style={styles.count}>{settings.connectionCount}</span>
                <button
                    style={styles.stepper}
                    onClick={() => update({ connectionCount: Math.min(10, settings.connectionCount + 1) })}>
                    +
                </button>
            </label>

            <label>
                <input
                    type="checkbox"
                    checked={settings.directMode}
                    onChange={event => update({ directMode: event.target.checked })}
                />{' '}
                Direct mode
            </label>

            <label>
                Transfer mode{' '}
                <select
                    value={settings.transferMode}
                    onChange={event => update({ transferMode: event.target.value as ObservableQueryTransferMode })}>
                    <option value={ObservableQueryTransferMode.Delta}>Delta</option>
                    <option value={ObservableQueryTransferMode.Full}>Full</option>
                </select>
            </label>

            <span style={styles.divider} />

            <label>
                <input
                    type="checkbox"
                    checked={settings.signedIn}
                    onChange={event => update({ signedIn: event.target.checked })}
                />{' '}
                Signed in
            </label>

            <label>
                User ID{' '}
                <input
                    value={settings.userId}
                    onChange={event => update({ userId: event.target.value })}
                    style={{ width: 120 }}
                />
            </label>

            <label>
                Name{' '}
                <input
                    value={settings.userName}
                    onChange={event => update({ userName: event.target.value })}
                    style={{ width: 140 }}
                />
            </label>

            <label>
                Roles{' '}
                <input
                    value={settings.roles}
                    onChange={event => update({ roles: event.target.value })}
                    style={{ width: 200 }}
                />
            </label>
        </div>
    );
};

const styles: Record<string, React.CSSProperties> = {
    bar: {
        padding: '8px 16px',
        background: '#f0f0f0',
        borderBottom: '1px solid #ccc',
        display: 'flex',
        gap: 16,
        alignItems: 'center',
        fontSize: 14,
        flexWrap: 'wrap'
    },
    inline: { display: 'flex', alignItems: 'center', gap: 4 },
    stepper: { width: 24, cursor: 'pointer' },
    count: { minWidth: 20, textAlign: 'center' },
    divider: { borderLeft: '1px solid #ccc', height: 20 }
};
