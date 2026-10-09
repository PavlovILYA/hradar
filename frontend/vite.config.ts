import { defineConfig } from 'vite';

const localServer = {
  host: '127.0.0.1',
  port: 5173,
  strictPort: true,
  proxy: { '/api': 'http://127.0.0.1:8080' },
};

export default defineConfig({ server: localServer, preview: localServer });
