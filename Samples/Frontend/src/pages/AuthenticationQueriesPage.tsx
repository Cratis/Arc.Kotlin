// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useIdentity } from '@cratis/arc.react/identity';
import { Anonymous } from '../generated/features/authenticationqueries/Anonymous';
import { Authenticated } from '../generated/features/authenticationqueries/Authenticated';
import { Section } from './shared';

/**
 * One protected read model with one deliberately open query.
 *
 * A login screen has to show something before anyone has signed in. `AuthenticationQueryItem` is
 * annotated `@Authorize` at the class, and `anonymous` carries `@AllowAnonymous` — the operation
 * overrides the class rather than being overruled by it, which is what makes this shape possible
 * without opening the rest of the model.
 *
 * Start the backend with `cratis.arc.samples.default-identity=false` to see the signed-out side of
 * this page; `./run.sh` does that for you.
 */
export const AuthenticationQueriesPage = () => {
    const identity = useIdentity();
    const [anonymousResult] = Anonymous.use();
    const [authenticatedResult] = Authenticated.when(identity.isSet).use();

    return (
        <div>
            <h2>Authentication Queries</h2>
            <p>
                The anonymous stream must keep running while signed out. The authenticated one must not
                start at all.
            </p>

            <Section title="Anonymous query">
                <p>
                    <code>AuthenticationQueryItem.anonymous()</code>, annotated <code>@AllowAnonymous</code>.
                </p>
                {anonymousResult.isPerforming && <p>Connecting…</p>}
                {anonymousResult.hasData && (
                    <p>
                        <strong>{anonymousResult.data.message}</strong>
                    </p>
                )}
            </Section>

            <div style={{ height: 16 }} />

            <Section title="Authenticated query">
                <p>
                    <code>AuthenticationQueryItem.authenticated()</code>, which inherits the class-level{' '}
                    <code>@Authorize</code>.
                </p>
                {!identity.isSet && (
                    <p style={{ color: '#888', margin: 0 }}>
                        Sign in from the toolbar to activate this query.
                    </p>
                )}
                {identity.isSet && authenticatedResult.isPerforming && <p>Connecting…</p>}
                {identity.isSet && authenticatedResult.hasData && (
                    <p>
                        <strong>{authenticatedResult.data.message}</strong>
                    </p>
                )}
            </Section>
        </div>
    );
};
