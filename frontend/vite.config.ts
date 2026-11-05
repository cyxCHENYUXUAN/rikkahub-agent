import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: 'localhost', port: 5173, strictPort: true,
    proxy: { '/api': { target: `http://127.0.0.1:${process.env.SERVER_PORT || '8081'}`, changeOrigin: true } },
  },
  preview: {
    host: 'localhost', port: 5173, strictPort: true,
    proxy: { '/api': { target: `http://127.0.0.1:${process.env.SERVER_PORT || '8081'}`, changeOrigin: true } },
  },
})
