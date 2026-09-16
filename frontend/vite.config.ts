import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 백엔드(./gradlew bootRun, 기본 8080)를 로컬에서 같이 띄웠을 때 CORS 없이 바로 붙게 하는 프록시
    // /uploads 는 메뉴 사진(O9)·행사 약도(E2) 정적 파일 — 응답의 상대 주소(/uploads/menu/…)가 개발 환경에서도 보이게 함께 넘긴다
    proxy: {
      '/api': 'http://localhost:8080',
      '/uploads': 'http://localhost:8080',
    },
  },
})
