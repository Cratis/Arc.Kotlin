// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { ObservableQueryTransferMode } from '@cratis/arc';
import { IdentityProvider } from '@cratis/arc/identity';
import { QueryTransportMethod, resetSharedMultiplexer } from '@cratis/arc/queries';
import { Arc } from '@cratis/arc.react';
import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import type { Page } from './App';
import { Toolbar } from './Toolbar';
import type { Settings } from './Toolbar';
import { clearAuthCookie, setAuthCookie, encodeClientPrincipal } from './authentication';
import { loadSettings, saveSettings } from './settings';

const Root = () => {
    const [settings, setSettings] = useState<Settings>(() => loadSettings());
    const [identityKey, setIdentityKey] = useState(0);
    const [page, setPage] = useState<Page>('taskboard');

    useEffect(() => {
        applyCredentials(settings);
    }, []);

    const apply = (next: Settings) => {
        // A change to the transport or to who you are needs a whole new Arc context, and identity is
        // in that list for a reason worth knowing: a query instance captures the credentials it was
        // created with, and an observable subscription was authorized once, at handshake. Editing the
        // roles box alone would leave every already-created query and command sending the old
        // principal. Remounting is what makes "become someone else" actually mean it.
        const reconnect =
            next.transport !== settings.transport ||
            next.connectionCount !== settings.connectionCount ||
            next.directMode !== settings.directMode ||
            next.transferMode !== settings.transferMode ||
            next.signedIn !== settings.signedIn ||
            next.userId !== settings.userId ||
            next.userName !== settings.userName ||
            next.roles !== settings.roles;

        setSettings(next);
        saveSettings(next);
        applyCredentials(next);
        if (reconnect) {
            setIdentityKey(current => current + 1);
        }
    };

    const httpHeadersCallback = (): Record<string, string> => {
        if (!settings.signedIn) {
            return {};
        }
        return {
            'x-ms-client-principal-id': settings.userId,
            'x-ms-client-principal-name': settings.userName,
            'x-ms-client-principal': encodeClientPrincipal(settings)
        };
    };

    return (
        <StrictMode>
            <Toolbar settings={settings} onChange={apply} />
            <Arc
                development={true}
                key={identityKey}
                queryTransportMethod={settings.transport}
                queryConnectionCount={settings.connectionCount}
                queryDirectMode={settings.directMode}
                observableQueryTransferMode={settings.transferMode}
                httpHeadersCallback={httpHeadersCallback}>
                <App page={page} onPageChange={setPage} />
            </Arc>
        </StrictMode>
    );
};

/**
 * Writes the credentials the next Arc context will use, and drops everything holding the old ones.
 *
 * Three pieces of state outlive a React re-render here, and all three have to go:
 *
 * - `x-ms-client-principal` is what this sample sends *to* the server.
 * - `.cratis-identity` is Arc's own client-readable cache of what the server last said about you.
 * - the shared multiplexer is one WebSocket connection, authorized once at handshake, that every
 *   subscription rides. Leave it in place and a subscription opened after signing in travels a
 *   connection the server still considers anonymous — which it correctly refuses.
 */
const applyCredentials = (settings: Settings): void => {
    IdentityProvider.clearIdentityCookie();
    resetSharedMultiplexer();
    if (settings.signedIn) {
        setAuthCookie(settings);
    } else {
        clearAuthCookie();
    }
};

createRoot(document.getElementById('root')!).render(<Root />);
