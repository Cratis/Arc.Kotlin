// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { useState } from 'react';
import { CompleteTask } from '../generated/CompleteTask';
import { CreateTask } from '../generated/CreateTask';
import { Observe } from '../generated/Observe';
import type { TaskView } from '../generated/TaskView';
import { Section, tableStyles } from './shared';

/**
 * The task board — a command that validates, a command that prepares, and a live list.
 *
 * `CreateTask` shows what a rejected command looks like end to end: submit an empty title and the
 * validation results come back on the same envelope as a success would, already bound to the field
 * that caused them. `CompleteTask` shows the `provide` step — it loads the task and its revision
 * before `handle` runs, so completing a task someone else already changed is refused instead of
 * silently overwriting.
 */
export const TaskBoardPage = () => {
    const [result] = Observe.use();
    const [createCommand, setCreateValues] = CreateTask.use();
    const [completeCommand, setCompleteValues] = CompleteTask.use();
    const [messages, setMessages] = useState<string[]>([]);

    const tasks = result.data ?? [];

    const create = async () => {
        const outcome = await createCommand.execute();
        if (outcome.isSuccess) {
            setMessages(current => [`Created "${createCommand.title}"`, ...current].slice(0, 5));
            setCreateValues({ title: '' });
        } else {
            setMessages(current =>
                [
                    ...outcome.validationResults.map(validation => `Rejected: ${validation.message}`),
                    ...current
                ].slice(0, 5)
            );
        }
    };

    const complete = async (task: TaskView) => {
        setCompleteValues({ taskId: task.id });
        const outcome = await completeCommand.execute();
        if (!outcome.isSuccess) {
            setMessages(current =>
                [
                    ...outcome.validationResults.map(validation => `Rejected: ${validation.message}`),
                    ...current
                ].slice(0, 5)
            );
        }
    };

    return (
        <div>
            <h2>Task Board</h2>
            <p>
                Two commands and one observable query. Nothing here refetches after a mutation — the
                board is subscribed to <code>TaskView.observe()</code>, so a task created from another
                browser tab, or by <code>curl</code>, appears here too.
            </p>

            <Section title="Create a task">
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <input
                        placeholder="Title"
                        value={createCommand.title ?? ''}
                        onChange={event => setCreateValues({ title: event.target.value })}
                        style={{ width: 280 }}
                    />
                    <button onClick={create}>Create</button>
                    <span style={{ color: '#888', fontSize: 12 }}>
                        Submit an empty title to see server validation.
                    </span>
                </div>
                {messages.length > 0 && (
                    <ul style={{ marginTop: 12, marginBottom: 0, paddingLeft: 18, fontSize: 13 }}>
                        {messages.map((message, index) => (
                            <li key={index} style={{ color: message.startsWith('Rejected') ? '#b00' : '#2a7c2a' }}>
                                {message}
                            </li>
                        ))}
                    </ul>
                )}
            </Section>

            <div style={{ height: 16 }} />

            <Section title="Board">
                {result.isPerforming && <p>Connecting…</p>}
                {!result.isPerforming && tasks.length === 0 && <p>No tasks yet. Create one above.</p>}
                {tasks.length > 0 && (
                    <table style={tableStyles.table}>
                        <thead>
                            <tr style={tableStyles.headerRow}>
                                <th style={tableStyles.headerCell}>Title</th>
                                <th style={tableStyles.headerCell}>Status</th>
                                <th style={{ ...tableStyles.headerCell, textAlign: 'right' }}>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            {tasks.map(task => (
                                <tr key={task.id} style={tableStyles.row}>
                                    <td style={tableStyles.cell}>{task.title}</td>
                                    <td style={tableStyles.cell}>{task.completed ? 'Completed' : 'Open'}</td>
                                    <td style={tableStyles.actions}>
                                        {!task.completed && (
                                            <button onClick={() => complete(task)}>Complete</button>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </Section>
        </div>
    );
};
