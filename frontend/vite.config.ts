import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 백엔드(./gradlew bootRun, 기본 8080)를 로컬에서 같이 띄웠을 때 CORS 없이 바로 붙게 하는 프록시
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
