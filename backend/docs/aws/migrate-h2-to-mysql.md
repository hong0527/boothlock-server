# H2 → RDS MySQL 데이터 이전

후보 절차. **먼저 정해야 하는 것 하나**가 있고, 그 답에 따라 난이도가 완전히 달라진다.

---

## 0. QR을 이미 인쇄했는가

이게 갈림길이다.

`booth_table.table_token` 은 **인쇄된 QR에 박혀 있는 비밀값**이다. 손님이 QR을 찍으면
`https://booothlook.com/t/{tableToken}` 으로 들어오고, 서버가 그 토큰으로 테이블을 찾는다.
다시 시딩하면 토큰이 새로 생성되므로 **인쇄된 QR이 전부 죽는다.** 다시 인쇄할 수 없다면 복구 불가다.

| 상황 | 해야 할 일 | 난이도 |
|---|---|---|
| **QR 인쇄 전** | RDS에 `schema-mysql8.sql` 적용 → `BOOTLOCK_SEED_ENABLED=true` 로 1회 기동 → 운영자가 테이블 등록·QR 다운로드. **데이터 이전이 아예 없다** | 쉬움 |
| **QR 인쇄 후, 주문 없음** | `booth`·`staff_account`·`booth_table`·`menu`·`event_map` 5개 테이블만 옮긴다. 주문·세션은 버려도 된다 | 보통 |
| **축제 중(주문 있음)** | **하지 않는다.** §4 참조 | — |

> 권고: **QR 인쇄 전에 RDS 전환을 끝낸다.** 그러면 이 문서의 나머지가 필요 없다.
> 지금이 축제 전이고 아직 인쇄하지 않았다면, 이 순서를 지키는 것만으로 가장 성가신 작업이 사라진다.

---

## 1. 옮겨야 하는 경우 — 준비

EC2에서, **API 컨테이너를 멈춘 상태로** 한다. H2가 파일을 쓰는 도중에 읽으면 일관성이 깨진다.

```bash
cd ~/boothlock-server
docker compose -f docker-compose.prod.yml stop api
cp -a data/db data/db.backup-$(date +%F-%H%M)   # 무슨 일이 있어도 되돌아갈 자리
```

H2 콘솔 도구는 앱 jar 안의 드라이버로 쓴다. H2 jar를 따로 받는 편이 쉽다:

```bash
curl -sLO https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar
```

> 버전 주의: H2는 파일 포맷이 메이저 버전 간에 호환되지 않는다. 앱이 쓰는 H2 버전과 맞춰야 한다.
> 확인: `docker compose -f docker-compose.prod.yml run --rm --entrypoint sh api -c 'unzip -l /app/app.jar | grep h2-'`

---

## 2. H2에서 CSV로 뽑기

H2의 `CSVWRITE` 를 쓴다. `null=\N` 옵션이 핵심이다 — 이걸 안 주면 NULL이 빈 문자열로 나가고,
MySQL이 숫자 컬럼에 빈 문자열을 받아 **경고만 내면서 0으로 넣는다**. `ended_at`(NULL이 "활성 세션"을
뜻한다)이 0으로 바뀌면 모든 세션이 종료된 것으로 보인다.

```bash
mkdir -p ~/h2dump && cd ~/h2dump

# 인쇄된 QR을 살리는 데 필요한 5개 테이블. 주문·세션까지 옮기려면 아래 목록에 더한다
for T in booth staff_account booth_table menu event_map; do
  java -cp ~/h2-2.3.232.jar org.h2.tools.Shell \
    -url "jdbc:h2:file:$HOME/boothlock-server/data/db/boothlock;MODE=MySQL;ACCESS_MODE_DATA=r" \
    -user sa -password "" \
    -sql "CALL CSVWRITE('$HOME/h2dump/$T.csv', 'SELECT * FROM $T ORDER BY id', 'charset=UTF-8 null=\N');"
  echo "$T: $(( $(wc -l < $T.csv) - 1 )) 행"
done
```

`ACCESS_MODE_DATA=r` 로 읽기 전용으로 연다 — 실수로 파일을 건드리지 않는다.

**첫 줄이 컬럼 이름**이다. MySQL 쪽 컬럼 순서와 같은지 눈으로 확인한다:

```bash
head -1 booth_table.csv
# ID,BOOTH_ID,LABEL,TABLE_TOKEN,STATUS,POS_X,POS_Y,ACTIVE  ← 이름이 대문자다. 순서가 중요하다
```

---

## 3. MySQL에 넣기

먼저 스키마를 적용한다 (`배포_운영절차.md` §3-2와 같다):

```bash
mysql -h <RDS> -u <admin> -p -e "CREATE DATABASE boothlock CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -h <RDS> -u <admin> -p boothlock < backend/docs/schema-mysql8.sql
```

그 다음 CSV를 넣는다. **BOOLEAN 컬럼이 문제다** — H2 CSVWRITE는 `TRUE`/`FALSE` 문자열로 쓰는데
MySQL의 `tinyint(1)`에 그 문자열을 그대로 넣으면 경고와 함께 **전부 0**이 된다. `active`가 0이 되면
모든 테이블이 soft delete 된 것으로 보여 QR을 찍어도 "없는 테이블"이 나온다. 그래서 해당 컬럼만
변수로 받아서 변환한다.

```sql
-- mysql -h <RDS> -u <admin> -p --local-infile=1 boothlock < 이 파일
-- (서버에서 local_infile=ON 이어야 한다. RDS는 파라미터 그룹에서 켠다. 못 켜면 §3-대안 참조)
SET FOREIGN_KEY_CHECKS = 0;

-- ※※ 아래 괄호 안 컬럼 순서는 **반드시 위 `head -1` 결과로 덮어써야 한다.** ※※
--   여기 적은 것은 schema-mysql8.sql 의 선언 순서다. H2가 CSV에 쓰는 순서는 엔티티 필드 순서를
--   따르므로 다를 수 있고, 순서가 어긋나면 에러 없이 **값이 엉뚱한 컬럼에 들어간다**
--   (예: bank_account 자리에 부스 이름). 한 테이블씩 헤더를 보고 고쳐 넣는다.

LOAD DATA LOCAL INFILE 'booth.csv' INTO TABLE booth
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' LINES TERMINATED BY '\n' IGNORE 1 LINES
  (id, name, bank_account, depositor_name, @is_open, operating_hours, category,
   map_x, map_y, next_table_seq)
  SET is_open = (@is_open = 'TRUE');

LOAD DATA LOCAL INFILE 'staff_account.csv' INTO TABLE staff_account
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' LINES TERMINATED BY '\n' IGNORE 1 LINES
  (id, booth_id, login_id, password_hash, password_changed_at, role, @active,
   failed_login_count, locked_until)
  SET active = (@active = 'TRUE');

LOAD DATA LOCAL INFILE 'booth_table.csv' INTO TABLE booth_table
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' LINES TERMINATED BY '\n' IGNORE 1 LINES
  (id, booth_id, label, table_token, status, pos_x, pos_y, @active)
  SET active = (@active = 'TRUE');

LOAD DATA LOCAL INFILE 'menu.csv' INTO TABLE menu
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' LINES TERMINATED BY '\n' IGNORE 1 LINES
  (id, booth_id, name, price, image_url, description, @sold_out, @visible, category)
  SET sold_out = (@sold_out = 'TRUE'), visible = (@visible = 'TRUE');

LOAD DATA LOCAL INFILE 'event_map.csv' INTO TABLE event_map
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' LINES TERMINATED BY '\n' IGNORE 1 LINES
  (id, image_url, width, height, updated_at);

SET FOREIGN_KEY_CHECKS = 1;
```

`staff_account.password_hash` 는 BCrypt 해시라 `$2a$...` 형태의 문자열이다. CSV에서 따옴표가
제대로 붙는지, `$` 가 셸에서 확장되지 않는지 확인한다 — 해시가 한 글자라도 다르면 그 운영자는
로그인하지 못하고, 비밀번호를 모르므로 고칠 방법이 없다.

**대안 (local_infile 을 못 켤 때)**: 행이 수백 개 규모라 그냥 INSERT 문으로 만들어도 된다.
`CSVWRITE` 대신 H2에서 직접 INSERT를 생성한다:

```bash
java -cp ~/h2-2.3.232.jar org.h2.tools.Script \
  -url "jdbc:h2:file:$HOME/boothlock-server/data/db/boothlock;MODE=MySQL" -user sa -password "" \
  -script ~/h2dump/dump.sql
```
그 뒤 `dump.sql` 에서 `INSERT INTO PUBLIC."BOOTH"` 같은 줄만 뽑아 `PUBLIC.`·큰따옴표를 지우고
백틱으로 바꾼다. 손이 가지만 눈으로 전부 확인할 수 있어서 이 규모에서는 오히려 안전하다.

---

## 4. 넣은 뒤 반드시 확인할 것

`AUTO_INCREMENT` 가 가장 흔한 사고다. `LOAD DATA`로 id를 명시해 넣으면 MySQL이 카운터를
따라 올려 주지만, 확인하지 않고 넘어가면 다음 INSERT가 **기존 id와 충돌**한다.

```sql
-- 1) 행 수가 H2와 같은가
SELECT 'booth', COUNT(*) FROM booth
UNION ALL SELECT 'staff_account', COUNT(*) FROM staff_account
UNION ALL SELECT 'booth_table', COUNT(*) FROM booth_table
UNION ALL SELECT 'menu', COUNT(*) FROM menu;

-- 2) BOOLEAN 이 전부 0으로 눕지 않았는가 (여기서 0이 나오면 §3의 변환이 안 먹은 것이다)
SELECT COUNT(*) AS active_tables FROM booth_table WHERE active = 1;
SELECT COUNT(*) AS open_booths   FROM booth       WHERE is_open = 1;

-- 3) 토큰이 대소문자까지 그대로인가 — 인쇄된 QR 하나를 골라 직접 대조한다
SELECT id, label, table_token FROM booth_table ORDER BY id;

-- 4) AUTO_INCREMENT 가 최대 id 보다 큰가
SELECT AUTO_INCREMENT FROM information_schema.tables
  WHERE table_schema='boothlock' AND table_name IN ('booth','staff_account','booth_table','menu');
SELECT MAX(id) FROM booth_table;
-- AUTO_INCREMENT 가 MAX(id) 이하면: ALTER TABLE booth_table AUTO_INCREMENT = <MAX(id)+1>;

-- 5) 한글이 깨지지 않았는가
SELECT id, name FROM menu LIMIT 10;
```

그리고 **실제 QR 한 장으로 끝까지 해 본다**: 휴대폰으로 인쇄된 QR을 찍어 메뉴가 뜨고,
주문이 접수되고, 운영자 대시보드에 보이고, 입금확인·퇴실까지 한 바퀴 돈다.
이게 통과해야 이전이 끝난 것이다. 쿼리 다섯 개보다 이 한 바퀴가 더 믿을 만하다.

---

## 5. 축제 중에는 옮기지 않는다

축제가 시작돼 실제 주문이 쌓이기 시작하면 이전을 하지 않는다. 이유:

- `orders`·`order_item`·`table_session`·`daily_counter` 가 서로 얽혀 있고, `daily_counter` 는
  영업일별 주문 번호 카운터라 잘못 옮기면 **주문번호가 중복**된다. 손님과 부스가 같은 번호를 두고 다툰다.
- 이전하는 동안 서비스를 멈춰야 하는데, 멈춘 사이에 들어온 주문은 어디에도 남지 않는다.
- 되돌릴 때 "H2에는 있고 MySQL에는 없는 주문"과 그 반대가 동시에 생긴다.

축제 중 DB를 바꿔야 할 정도의 상황이면, 바꾸는 대신 `배포_운영절차.md` §6의 tar 스냅샷으로
같은 H2 구성을 새 EC2에 올리는 쪽이 빠르고 안전하다.
