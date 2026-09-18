// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useState } from 'react';
import { All } from '../generated/features/livefeed/All';
import { ByAuthor } from '../generated/features/livefeed/ByAuthor';
import type { LiveFeed } from '../generated/features/livefeed/LiveFeed';
import { PostToFeed } from '../generated/features/livefeed/PostToFeed';
import { Columns, Section, formatTime } from './shared';

/**
 * A command whose effect is watched, not returned.
 *
 * `PostToFeed` answers its own caller with the posted message. Everyone else finds out because the
 * feed they subscribed to pushes a new value. Those are two different channels for the same event,
 * and seeing both in one page is the quickest way to stop conflating them.
 */
export const LiveFeedPage = () => {
    const [allResult] = All.use();
    const [authorFilter, setAuthorFilter] = useState('');
    const [byAuthorResult] = ByAuthor.when(authorFilter.length > 0).use({ author: authorFilter });
    const [command, setValues] = PostToFeed.use();
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const post = async () => {
        setError('');
        setSuccess('');
        const result = await command.execute();
        if (result.isSuccess) {
            setSuccess(`Posted at ${formatTime(result.response?.postedAt)}`);
            setValues({ author: command.author, text: '' });
        } else {
            setError('The command did not succeed. Check the backend log.');
        }
    };

    return (
        <div>
            <h2>Live Feed</h2>

            <Section title="Post a message">
                <p>
                    Executes <code>PostToFeed</code>. The response returns to this caller; the updated feed is
                    pushed to every subscriber, including the panels below.
                </p>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 400 }}>
                    <input
                        placeholder="Author"
                        value={command.author ?? ''}
                        onChange={event => setValues({ author: event.target.value })}
                    />
                    <input
                        placeholder="Message"
                        value={command.text ?? ''}
                        onChange={event => setValues({ text: event.target.value })}
                    />
                    <button onClick={post} disabled={!command.author || !command.text}>
                        Post
                    </button>
                    {success && <p style={{ color: 'green', margin: 0 }}>{success}</p>}
                    {error && <p style={{ color: 'red', margin: 0 }}>{error}</p>}
                </div>
            </Section>

            <div style={{ height: 24 }} />

            <Columns>
                <Section title="All messages">
                    <p>
                        Subscribes to <code>LiveFeed.all()</code>.
                    </p>
                    {allResult.isPerforming && <p>Connecting…</p>}
                    <MessageList messages={allResult.data ?? []} />
                </Section>

                <Section title="Filtered by author">
                    <p>
                        Subscribes to <code>LiveFeed.byAuthor(author)</code> only once an author is entered —
                        an observable query binds arguments exactly as a one-shot query does.
                    </p>
                    <input
                        placeholder="Author to filter"
                        value={authorFilter}
                        onChange={event => setAuthorFilter(event.target.value)}
                        style={{ marginBottom: 8, width: '100%', boxSizing: 'border-box' }}
                    />
                    {!authorFilter && <p style={{ color: '#888' }}>Enter an author name to activate.</p>}
                    {authorFilter && byAuthorResult.isPerforming && <p>Connecting…</p>}
                    {authorFilter && <MessageList messages={byAuthorResult.data ?? []} />}
                </Section>
            </Columns>
        </div>
    );
};

const MessageList = ({ messages }: { messages: LiveFeed[] }) => {
    if (messages.length === 0) {
        return <p style={{ color: '#888' }}>No messages yet.</p>;
    }

    return (
        <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {[...messages].reverse().map((message, index) => (
                <li key={index} style={{ borderBottom: '1px solid #eee', padding: '8px 0' }}>
                    <strong>{message.author}</strong>
                    {' — '}
                    {message.text}
                    <br />
                    <small style={{ color: '#888' }}>{formatTime(message.postedAt)}</small>
                </li>
            ))}
        </ul>
    );
};
