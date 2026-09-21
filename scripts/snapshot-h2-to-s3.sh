#!/usr/bin/env bash
# 후보 스크립트: H2 파일 DB와 업로드 이미지를 S3에 자동 백업한다. **RDS를 안 쓰기로 했을 때의 대안**이다.
#
# 두는 곳: scripts/snapshot-h2-to-s3.sh (EC2 위)
# 쓰는 법: BUCKET=booothlook-backup ./scripts/snapshot-h2-to-s3.sh
# 자동화:  crontab -e
#          */30 * * * * BUCKET=booothlook-backup /home/ubuntu/boothlock-server/scripts/snapshot-h2-to-s3.sh >> /var/log/boothlock-snapshot.log 2>&1
#
# ── 이게 무엇을 해결하나 ─────────────────────────────────────────────────────────────
# 지금 구조에서 EC2가 죽으면 주문·입금 기록이 통째로 사라진다. 배포_운영절차.md §6에 수동 tar 절차가
# 있지만 "개장 전·폐장 후"라 하루 반나절 단위이고, 사람이 기억해야 하며, **백업이 EC2 안에 있어서
# EC2가 죽으면 백업도 같이 죽는다.** 이 스크립트는 셋 다 고친다 — 30분 간격, 자동, S3에 보관.
#
# RDS와 비교하면: 복구 지점이 30분 단위(RDS는 5분 단위 PITR)이고, 복구는 손으로 파일을 되돌리는
# 것이라 10~20분 걸린다(RDS는 콘솔에서 복원). 대신 비용이 사실상 0이고, DB 엔진을 바꾸지 않으므로
# MySQL로 옮길 때 다시 나타나는 위험(격리수준·콜레이션, 배포문서 §3-4)을 아예 건드리지 않는다.
#
# ── 서비스를 멈추지 않는다 ───────────────────────────────────────────────────────────
# 배포문서의 수동 절차는 "컨테이너를 멈추고 tar"인데, 30분마다 그러면 손님이 주문하는 도중에
# 계속 끊긴다. 여기서는 파일을 그대로 복사한 뒤 **복사본이 실제로 열리는지 검증**한다.
# H2의 MVStore는 커밋 프로토콜이 있어서 쓰기 도중 복사해도 대개 정전 복구와 같은 방식으로 열린다.
# 열리지 않으면 이 스크립트가 알아채고 다시 시도하므로, "깨진 백업을 백업이라고 믿는" 상황이 없다.
set -euo pipefail

BUCKET="${BUCKET:?BUCKET 환경변수가 필요합니다 (예: booothlock-backup)}"
APP_DIR="${APP_DIR:-$HOME/boothlock-server}"
H2_JAR="${H2_JAR:-$HOME/h2-2.3.232.jar}"   # 검증용. 없으면 검증을 건너뛰고 경고만 남긴다
KEEP_LOCAL="${KEEP_LOCAL:-6}"              # 로컬에 남길 스냅샷 개수 (30분 간격이면 3시간치)
STAMP="$(date +%F-%H%M)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$APP_DIR"
test -d data/db || { echo "[$STAMP] data/db 가 없습니다 — APP_DIR을 확인하세요"; exit 1; }

# 1) 복사. rsync 가 아니라 cp -a 로 한 번에 — 중간 상태를 줄인다
cp -a data/db "$WORK/db"

# 2) 검증: 복사본이 실제로 열리고 주문 테이블을 읽을 수 있는가.
#    여기서 실패하면 백업으로 쓸 수 없는 파일이다 — 올리지 않고 0이 아닌 코드로 끝내 cron 로그에 남긴다.
if [ -f "$H2_JAR" ]; then
  ROWS=$(java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "jdbc:h2:file:$WORK/db/boothlock;MODE=MySQL" -user sa -password "" \
    -sql "SELECT COUNT(*) FROM orders;" 2>&1 | tr -d ' ' | grep -E '^[0-9]+$' | head -1) || true
  if [ -z "${ROWS:-}" ]; then
    echo "[$STAMP] 경고: 스냅샷을 열지 못했습니다. 올리지 않고 종료합니다 (다음 주기에 재시도)"
    exit 1
  fi
  echo "[$STAMP] 검증 통과 — orders $ROWS 행"
else
  echo "[$STAMP] 경고: H2_JAR($H2_JAR)이 없어 검증을 건너뜁니다. 검증 없는 백업은 믿을 수 없습니다"
fi

# 3) 업로드 이미지도 같이. 메뉴 사진이 사라지면 메뉴판이 빈칸이 된다
#    (DB에는 image_url 만 있고 실제 파일은 디스크에 있다)
cp -a data/uploads "$WORK/uploads"

# 4) 묶어서 S3로
TARBALL="$WORK/boothlock-$STAMP.tgz"
tar czf "$TARBALL" -C "$WORK" db uploads
aws s3 cp "$TARBALL" "s3://$BUCKET/snapshots/boothlock-$STAMP.tgz" \
  --storage-class STANDARD_IA --only-show-errors
echo "[$STAMP] 업로드 완료: s3://$BUCKET/snapshots/boothlock-$STAMP.tgz ($(du -h "$TARBALL" | cut -f1))"

# 5) 로컬 사본도 남긴다 — S3가 안 되는 상황에서도 최근 것은 손에 있어야 한다
mkdir -p backups
cp "$TARBALL" "backups/"
ls -1t backups/boothlock-*.tgz 2>/dev/null | tail -n +$((KEEP_LOCAL + 1)) | xargs -r rm --

# ── 복구 절차 (축제 중에 이걸 읽게 되면 이미 급한 상황이다) ─────────────────────────
#   1. docker compose -f docker-compose.prod.yml stop api
#   2. mv data/db data/db.broken-$(date +%F-%H%M)        ← 지우지 말고 옮긴다. 원인 파악에 필요하다
#   3. aws s3 ls s3://$BUCKET/snapshots/ | tail          ← 가장 최근 것 확인
#   4. aws s3 cp s3://$BUCKET/snapshots/<파일> /tmp/ && tar xzf /tmp/<파일> -C /tmp
#   5. cp -a /tmp/db data/db && sudo chown -R 10001:10001 data
#   6. docker compose -f docker-compose.prod.yml start api
#   7. 부스마다 "몇 시 이후 주문은 다시 받아야 한다"를 공지한다 — 스냅샷 이후 주문은 없다.
#      이게 이 방식의 실제 한계다. 최대 30분치 주문이 사라지고, 그 사실을 사람이 알려야 한다.
