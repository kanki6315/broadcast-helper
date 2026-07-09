import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Forward API calls to the Spring Boot backend so the app is CORS-free
    // in development and the frontend only ever talks to relative /api URLs.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
