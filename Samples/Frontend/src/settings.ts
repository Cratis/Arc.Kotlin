// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { ObservableQueryTransferMode } from '@cratis/arc';
import { QueryTransportMethod } from '@cratis/arc/queries';
import type { Settings } from './Toolbar';

const STORAGE_KEY = 'arc-samples.settings';

const defaults: Settings = {
    transport: QueryTransportMethod.WebSocket,
    connectionCount: 1,
    directMode: false,
    transferMode: ObservableQueryTransferMode.Delta,
    signedIn: false,
    userId: 'demo-user',
    userName: 'Demo User',
    roles: 'User'
};

/**
 * Reads the toolbar settings the browser remembered.
 *
 * Kept deliberately forgiving: a stored value from an older build must never leave the page unable
 * to start, so anything unrecognized falls back to the default rather than throwing.
 */
export const loadSettings = (): Settings => {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) {
        return defaults;
    }
    try {
        const parsed = JSON.parse(stored) as Partial<Settings>;
        return {
            transport:
                parsed.transport === QueryTransportMethod.ServerSentEvents
                    ? QueryTransportMethod.ServerSentEvents
                    : QueryTransportMethod.WebSocket,
            connectionCount: clamp(parsed.connectionCount, 1, 10, defaults.connectionCount),
            directMode: parsed.directMode === true,
            transferMode:
                parsed.transferMode === ObservableQueryTransferMode.Full
                    ? ObservableQueryTransferMode.Full
                    : ObservableQueryTransferMode.Delta,
            signedIn: parsed.signedIn === true,
            userId: text(parsed.userId, defaults.userId),
            userName: text(parsed.userName, defaults.userName),
            roles: text(parsed.roles, defaults.roles)
        };
    } catch {
        return defaults;
    }
};

/** Remembers the toolbar settings across a reload. */
export const saveSettings = (settings: Settings): void => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
};

const clamp = (value: number | undefined, min: number, max: number, fallback: number): number =>
    typeof value === 'number' && Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback;

const text = (value: string | undefined, fallback: string): string =>
    typeof value === 'string' && value.length > 0 ? value : fallback;
