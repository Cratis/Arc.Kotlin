// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import type { Settings } from './Toolbar';

const COOKIE_NAME = 'x-ms-client-principal';

/**
 * Encodes a Microsoft Identity Platform client principal the way a hosting platform would.
 *
 * Nothing here is a credential — it is base64, not a signature, and the sample's authentication
 * handler trusts it precisely because the sample runs on loopback. A real deployment puts a
 * gateway in front that signs or injects this header, and Arc's platform-identity support has a
 * deny-by-default trust policy for exactly that reason.
 */
export const encodeClientPrincipal = (settings: Settings): string =>
    btoa(
        JSON.stringify({
            identityProvider: 'sample',
            userId: settings.userId,
            userDetails: settings.userName,
            userRoles: settings.roles
                .split(',')
                .map(role => role.trim())
                .filter(role => role.length > 0),
            claims: []
        })
    );

/**
 * Writes the principal as a cookie as well as a header.
 *
 * `EventSource` and the WebSocket handshake cannot send a custom header, so a cookie is the only
 * way an observable query subscription carries the caller's identity.
 */
export const setAuthCookie = (settings: Settings): void => {
    document.cookie = `${COOKIE_NAME}=${encodeClientPrincipal(settings)}; path=/; SameSite=Lax`;
};

/** Removes the principal cookie so the next request is anonymous. */
export const clearAuthCookie = (): void => {
    document.cookie = `${COOKIE_NAME}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
};
