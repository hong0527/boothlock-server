# syntax=docker/dockerfile:1
# 부스락 프론트 이미지 — 정적 빌드(Vite) 후 nginx로 서빙. 백엔드와 같은 EC2에 컨테이너로 같이 띄운다.
#   빌드:  docker build -t boothlock-frontend:latest --build-arg VITE_API_BASE_URL=http://<EC2>:8080 frontend/
#   실행:  루트의 docker-compose.prod.yml 참조

# ---------- 1단계: 빌드 ----------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Vite는 빌드 시점에 환경변수를 코드에 박아 넣는다 — 나중에 컨테이너 환경변수를 바꿔도 반영 안 됨(재빌드 필요)
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
# 회원가입 화면 노출 — 기본 꺼짐. 시연·심사 때만 --build-arg VITE_SIGNUP_ENABLED=true 로 다시 빌드한다
# (백엔드도 BOOTLOCK_SIGNUP_ENABLED로 따로 켜야 한다 — 실제 차단막은 그쪽이다)
ARG VITE_SIGNUP_ENABLED="false"
ENV VITE_SIGNUP_ENABLED=$VITE_SIGNUP_ENABLED
RUN npm run build

# ---------- 2단계: 서빙 ----------
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

# 80은 443으로 리다이렉트되므로(nginx.conf), IP로 접속하면 인증서의 CN(도메인)과 안 맞아 SSL 검증에서
# 실패한다 — --no-check-certificate로 검증을 건너뛰고 TLS 핸드셰이크/응답만 확인한다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-check-certificate -qO- https://127.0.0.1/ > /dev/null || exit 1
