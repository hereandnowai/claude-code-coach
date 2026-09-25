import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// BACKEND_PORT comes from the repo-root .env (the backend reads the same file) or the shell.
// Read here only to build the proxy target; nothing without a VITE_ prefix reaches the browser.
const rootEnv = loadEnv('development', path.resolve(import.meta.dirname, '..'), '')
const backendPort = rootEnv.BACKEND_PORT || '8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: `http://localhost:${backendPort}`, changeOrigin: false },
    },
  },
})
