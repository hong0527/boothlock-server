#!/usr/bin/env bash
# RDS를 만들기 **전에**, 로컬 MySQL로 prod,rds 프로필이 실제로 뜨는지 확인한다.
#
# 쓰는 법: ./scripts/verify-rds-profile.sh                  (로컬 mysqld를 직접 쓸 때)
#          MYSQL_PORT=3307 ./scripts/verify-rds-profile.sh  (docker-compose.local-mysql.yml 을 띄워 놓았을 때)
#
# 왜 이걸 먼저 하나: RDS 인스턴스를 만들고 나서야 "스키마가 안 맞아 validate 실패"를 발견하면
# 콘솔 왕복이 길어진다. 같은 검증을 로컬에서 5분 안에 끝낼 수 있고, 배포_운영절차.md §0의
# 기존 실측(로컬 mysqld 8.4.0)과 같은 방식이다.
#
# 확인하는 것 네 가지:
#   1. schema-mysql8.sql 이 지금 엔티티와 맞는가 (ddl-auto=validate 가 기동에서 잡아낸다)
#   2. 토큰 3개 컬럼의 콜레이션이 utf8mb4_bin 인가 (ai_ci면 틀린 토큰으로 인증이 통과한다)
#   3. 커넥션 풀이 READ-COMMITTED 로 붙는가 (REPEATABLE READ면 동시성 결함 5건이 되살아난다)
#   4. 인증 없는 API가 200을 주는가
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_ADMIN="${MYSQL_ADMIN:-root}"
DB="${DB:-boothlock_verify}"
APP_USER="${APP_USER:-boothlock_app}"
APP_PW="${APP_PW:-verify-only-password}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCHEMA="$REPO_ROOT/backend/docs/schema-mysql8.sql"

echo "==> 0. 사전 확인"
command -v mysql >/dev/null || { echo "mysql 클라이언트가 없습니다"; exit 1; }
test -f "$SCHEMA" || { echo "스키마 파일이 없습니다: $SCHEMA"; exit 1; }
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ADMIN" -p -e "SELECT VERSION();" \
  || { echo "MySQL에 붙지 못했습니다"; exit 1; }

echo "==> 1. 검증용 DB·계정·스키마"
# 비밀번호를 매번 묻지 않으려면 ~/.my.cnf 에 [client] 블록을 두거나 MYSQL_PWD를 쓴다.
# (MYSQL_PWD는 ps에 보이지 않지만 경고가 뜬다. 검증용이라 이대로 둔다)
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ADMIN" -p <<SQL
DROP DATABASE IF EXISTS \`$DB\`;
CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$APP_USER'@'%' IDENTIFIED WITH caching_sha2_password BY '$APP_PW';
GRANT SELECT,INSERT,UPDATE,DELETE ON \`$DB\`.* TO '$APP_USER'@'%';
SQL
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ADMIN" -p "$DB" < "$SCHEMA"

echo "==> 2. 토큰 컬럼 콜레이션 — 세 줄이 나와야 한다"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ADMIN" -p -N -e "
  SELECT CONCAT(table_name,'.',column_name,' = ',collation_name)
  FROM information_schema.columns
  WHERE table_schema='$DB' AND collation_name='utf8mb4_bin';"
echo "    (booth_table.table_token / table_session.session_token / orders.idempotency_key)"
echo "    세 줄이 아니면 여기서 멈출 것 — 대소문자를 무시하는 토큰 비교는 틀린 토큰으로 인증을 통과시킨다"

echo "==> 3. prod,rds 프로필로 기동"
# application-rds.properties 가 backend/src/main/resources/ 에 있어야 한다
test -f "$REPO_ROOT/backend/src/main/resources/application-rds.properties" \
  || { echo "application-rds.properties 가 없습니다 — backend/docs/aws/ 의 후보 파일을 복사할 것"; exit 1; }

export SPRING_PROFILES_ACTIVE=prod,rds
export SPRING_DATASOURCE_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$DB?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci"
export SPRING_DATASOURCE_USERNAME="$APP_USER"
export SPRING_DATASOURCE_PASSWORD="$APP_PW"
# prod 프로필의 필수 환경변수 — 검증용 값이다. 절대 운영에 복사하지 않는다
export BOOTLOCK_JWT_SECRET="verify-only-secret-at-least-32-bytes-long-xxxx"
export BOOTLOCK_CUSTOMER_BASE_URL="https://booothlook.com"
export BOOTLOCK_CORS_ALLOWED_ORIGINS="https://booothlook.com"
export BOOTLOCK_SEED_ENABLED=false
export BOOTLOCK_UPLOAD_MENU_DIR="$REPO_ROOT/backend/data/uploads/menu"
export BOOTLOCK_UPLOAD_EVENT_DIR="$REPO_ROOT/backend/data/uploads/event"
mkdir -p "$BOOTLOCK_UPLOAD_MENU_DIR" "$BOOTLOCK_UPLOAD_EVENT_DIR"

LOG="$(mktemp -t boothlock-verify)"
( cd "$REPO_ROOT/backend" && ./gradlew --no-daemon bootRun > "$LOG" 2>&1 ) &
APP_PID=$!
# 종료 시 반드시 죽인다 — bootRun은 gradle 래퍼 아래에 JVM을 하나 더 띄운다
trap 'kill $APP_PID 2>/dev/null; pkill -f boothlock_server 2>/dev/null; true' EXIT

echo "    기동 대기 (최대 120초)..."
for i in $(seq 1 120); do
  if curl -fsS -o /dev/null http://127.0.0.1:8080/api/v1/event/booths 2>/dev/null; then break; fi
  if ! kill -0 $APP_PID 2>/dev/null; then
    echo "기동 실패 — 로그 마지막 40줄:"; tail -40 "$LOG"; exit 1
  fi
  sleep 1
done

echo "==> 4. 확인"
echo -n "    GET /api/v1/event/booths  : "; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/v1/event/booths
echo -n "    GET /swagger-ui/index.html: "; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/swagger-ui/index.html
echo "      (404 여야 한다 — prod 에서 springdoc이 꺼져 있다)"
echo -n "    GET /h2-console           : "; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/h2-console
echo "      (404 여야 한다)"

echo "==> 5. 커넥션 격리수준 — 붙어 있는 세션이 전부 READ-COMMITTED 여야 한다"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ADMIN" -p -e "
  SELECT p.id, t.variable_value AS isolation
  FROM information_schema.processlist p
  JOIN performance_schema.variables_by_thread t
    ON t.thread_id = (SELECT thread_id FROM performance_schema.threads WHERE processlist_id = p.id)
  WHERE p.db='$DB' AND t.variable_name='transaction_isolation';"
echo "    REPEATABLE-READ 가 하나라도 보이면 application-rds.properties 의"
echo "    spring.datasource.hikari.transaction-isolation 이 안 먹은 것이다 — 그대로 RDS에 올리면"
echo "    두 운영자가 서로 다른 주문 항목을 동시에 취소할 때 합계가 옛값으로 남는다 (배포문서 §3-4)"

echo
echo "==> 통과. 로그: $LOG"
echo "    다음 단계로 전체 테스트를 MySQL에서 한 번 더 돌리려면:"
echo "      cd backend && SPRING_PROFILES_ACTIVE=prod,rds ./gradlew test"
