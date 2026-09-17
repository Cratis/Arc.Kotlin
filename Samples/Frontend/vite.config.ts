// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The backend runs on :8080 by default; ./run.sh overrides ARC_BACKEND when you pick another port.
// Everything Arc owns is proxied to it, so the browser sees one origin and there is no CORS to
// configure — the same shape a production deployment has when Spring serves the built assets.
const backend = process.env.ARC_BACKEND ?? 'http://localhost:8080';

export default defineConfig({
    plugins: [react()],
    server: {
        port: Number(process.env.ARC_FRONTEND_PORT ?? 5173),
        strictPort: true,
        proxy: {
            '/api': { target: backend, changeOrigin: true, ws: true },
            '/.cratis': { target: backend, changeOrigin: true, ws: true }
        }
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true
    }
});
