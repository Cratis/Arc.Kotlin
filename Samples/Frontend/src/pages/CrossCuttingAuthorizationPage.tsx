// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useState } from 'react';
import { Secured } from '../generated/features/crosscuttingauthorization/Secured';
import { RunSecuredCommand } from '../generated/features/crosscuttingauthorization/RunSecuredCommand';
import { Section, formatTime } from './shared';

/**
 * Authorization decided for a whole feature, in one place.
 *
 * Neither the command nor the read model behind this page carries an authorization annotation. A
 * `CommandFilter` and a `QueryFilter` match on the package name and require a role, so adding a new
 * artifact to that package inherits the rule without anyone remembering to annotate it.
 *
 * Put `CrossCuttingAuthorization` in the toolbar's roles box and sign in to be allowed through;
 * remove it to watch both halves refuse.
 */
export const CrossCuttingAuthorizationPage = () => {
    const [command, setValues] = RunSecuredCommand.use({ message: 'Hello from the secured command' });
    const [commandResult, setCommandResult] = useState('');
    const [commandError, setCommandError] = useState('');
    const [queryResult] = Secured.use();
    const hasQueryData = !!queryResult.data?.message;

    const run = async () => {
        setCommandResult('');
        setCommandError('');

        const result = await command.execute();
        if (result.isSuccess) {
            setCommandResult(result.response ?? 'Command succeeded.');
            return;
        }
        if (!result.isAuthorized) {
            setCommandError(
                result.authorizationFailureReason || 'The custom command filter denied this command.'
            );
            return;
        }
        setCommandError('The command failed for a reason other than authorization.');
    };

    return (
        <div>
            <h2>Cross-Cutting Authorization</h2>
            <p>
                A <code>CommandFilter</code> and a <code>QueryFilter</code> guard every artifact in the{' '}
                <code>features.crosscuttingauthorization</code> package and require the{' '}
                <code>CrossCuttingAuthorization</code> role.
            </p>

            <Section title="Secured query">
                <p>
                    <code>CrossCuttingAuthorizationStatus.secured()</code>
                </p>
                {queryResult.isPerforming && <p>Loading…</p>}
                {!queryResult.isPerforming && !queryResult.isAuthorized && (
                    <p style={{ color: 'red', margin: 0 }}>
                        Denied by the custom query filter. Add the <code>CrossCuttingAuthorization</code> role
                        in the toolbar.
                    </p>
                )}
                {!queryResult.isPerforming && queryResult.isAuthorized && hasQueryData && (
                    <p style={{ margin: 0 }}>
                        <strong>{queryResult.data.message}</strong>
                        <br />
                        <span style={{ color: '#666' }}>{formatTime(queryResult.data.checkedAt)}</span>
                    </p>
                )}
                {!queryResult.isPerforming && queryResult.isAuthorized && !hasQueryData && (
                    <p style={{ color: '#666', margin: 0 }}>No secured query data yet.</p>
                )}
            </Section>

            <div style={{ height: 16 }} />

            <Section title="Secured command">
                <p>
                    <code>RunSecuredCommand</code>
                </p>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                    <input
                        value={command.message ?? ''}
                        onChange={event => setValues({ message: event.target.value })}
                        style={{ minWidth: 360 }}
                    />
                    <button onClick={run} disabled={!command.message?.trim()}>
                        Execute
                    </button>
                </div>
                {commandResult && <p style={{ color: 'green', margin: 0 }}>{commandResult}</p>}
                {commandError && <p style={{ color: 'red', margin: 0 }}>{commandError}</p>}
            </Section>
        </div>
    );
};
