-- =====================================================================================================
-- 부스락 운영 DB 스키마 — MySQL 8.0 (AWS RDS) / 검증: MySQL 8.4.0 로컬 + Hibernate(Spring Boot 4.1) ddl-auto=validate 기동 통과
--
-- 출처: PR #54 최종 엔티티(f5a4a21 — #53 메뉴 분류·#57 예금주명 포함)로 Hibernate가 MySQL 방언에서 생성한 DDL
--       (ddl-auto=create → mysqldump --no-data)을 바탕으로 DB스키마_v1.3.md 의 이름·순서로 정리하고, Hibernate가 만들 수 없는 것을 손으로 더했다:
--         ① table_token · session_token · idempotency_key 의 COLLATE utf8mb4_bin (대소문자 구분 — 기본 ai_ci면 틀린 토큰으로 인증 통과)
--         ② 문서의 FK 중 엔티티가 Long 컬럼으로만 참조해 Hibernate가 만들지 않은 것 (orders→booth/table_session, feedback→booth/staff_account, daily_counter→booth)
--         ③ 제약·인덱스 이름을 Hibernate 해시(FKokcuhbk…) 대신 읽을 수 있는 이름으로
--         ④ 문서의 DEFAULT 절 (Hibernate는 DEFAULT를 만들지 않고 엔티티 초기값으로 INSERT한다 — 수동 INSERT 편의)
--       타입은 Hibernate 검증(validate)이 통과하도록 Hibernate가 만든 것을 따른다 — DATETIME(6), 열거형 CHECK 제약.
--       문서(v1.3)와의 차이는 배포_운영절차.md §3 표 참조.
--
-- 적용: mysql -h <RDS> -u <admin> -p boothlock < schema-mysql8.sql   (DB는 미리: CREATE DATABASE boothlock CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;)
-- 규칙: 운영 DB는 ddl-auto=validate 로 기동한다. 엔티티를 바꾸면 이 파일에 ALTER 를 같이 넣는다 — 안 넣으면 기동이 실패해 알려 준다.
-- =====================================================================================================

SET NAMES utf8mb4;
SET time_zone = '+09:00';

CREATE TABLE booth (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  name            VARCHAR(50)  NOT NULL,
  bank_account    VARCHAR(100) NOT NULL,
  depositor_name  VARCHAR(50)  NULL,                        -- 예금주명 (#57, 문서 v1.3 미기재) — C3 계좌 안내에 병기
  is_open         BOOLEAN      NOT NULL DEFAULT TRUE,
  operating_hours VARCHAR(50)  NULL,
  category        VARCHAR(20)  NULL,
  map_x           INT          NULL,
  map_y           INT          NULL,
  next_table_seq  INT          NOT NULL DEFAULT 1,          -- 엔티티 columnDefinition "integer default 1" (문서 v1.3 미기재 — 테이블 자동 채번 O2)
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE staff_account (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  booth_id            BIGINT      NULL,                     -- SUPER_ADMIN 은 무소속
  login_id            VARCHAR(50) NOT NULL,
  password_hash       VARCHAR(72) NOT NULL,
  password_changed_at DATETIME(6) NOT NULL,
  role                VARCHAR(20) NOT NULL,
  active              BOOLEAN     NOT NULL DEFAULT TRUE,
  failed_login_count  INT         NOT NULL DEFAULT 0,
  locked_until        DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_staff_login_id (login_id),
  KEY idx_staff_booth (booth_id),
  CONSTRAINT fk_staff_booth FOREIGN KEY (booth_id) REFERENCES booth (id),
  CONSTRAINT chk_staff_role CHECK (role IN ('SUPER_ADMIN','ADMIN','STAFF'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE booth_table (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  booth_id    BIGINT      NOT NULL,
  label       VARCHAR(20) NOT NULL,
  table_token VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,    -- QR 비밀값: 대소문자 구분 필수
  status      VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
  pos_x       INT         NULL,
  pos_y       INT         NULL,
  active      BOOLEAN     NOT NULL DEFAULT TRUE,           -- soft delete (엔티티 columnDefinition "boolean default true", 문서 v1.3 미기재)
  PRIMARY KEY (id),
  UNIQUE KEY uq_booth_label (booth_id, label),
  UNIQUE KEY uq_table_token (table_token),
  CONSTRAINT fk_table_booth FOREIGN KEY (booth_id) REFERENCES booth (id),
  CONSTRAINT chk_table_status CHECK (status IN ('EMPTY','OCCUPIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE table_session (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  table_id         BIGINT      NOT NULL,
  session_token    VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, -- 손님 인증값: 대소문자 구분 필수
  started_at       DATETIME(6) NOT NULL,
  ended_at         DATETIME(6) NULL,                          -- NULL = 활성
  last_activity_at DATETIME(6) NOT NULL,
  ended_at_key     BIGINT      NOT NULL DEFAULT 0,            -- 활성 0, 종료 시 자기 id
  PRIMARY KEY (id),
  UNIQUE KEY uq_session_active (table_id, ended_at_key),      -- 테이블당 활성 세션 1개를 DB가 강제
  UNIQUE KEY uq_session_token (session_token),
  CONSTRAINT fk_session_table FOREIGN KEY (table_id) REFERENCES booth_table (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE menu (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  booth_id    BIGINT       NOT NULL,
  name        VARCHAR(50)  NOT NULL,
  price       INT          NOT NULL,
  image_url   VARCHAR(500) NULL,
  description VARCHAR(200) NULL,
  sold_out    BOOLEAN      NOT NULL DEFAULT FALSE,
  visible     BOOLEAN      NOT NULL DEFAULT TRUE,
  category    VARCHAR(20)  NULL,                            -- 엔티티에 있음 (문서 v1.3 미기재 — 메뉴 분류)
  PRIMARY KEY (id),
  UNIQUE KEY uk_menu_booth_name (booth_id, name),           -- 엔티티 @UniqueConstraint (문서 v1.3 미기재)
  CONSTRAINT fk_menu_booth FOREIGN KEY (booth_id) REFERENCES booth (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE orders (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  booth_id        BIGINT       NOT NULL,
  session_id      BIGINT       NULL,                        -- 수기 주문은 NULL
  table_label     VARCHAR(20)  NULL,
  order_no        VARCHAR(20)  NOT NULL,
  business_date   DATE         NOT NULL,
  order_seq       INT          NOT NULL,
  idempotency_key VARCHAR(64)  COLLATE utf8mb4_bin NULL,    -- 멱등키: NULL 허용 + UNIQUE + 대소문자 구분 (이 조합 유지)
  status          VARCHAR(20)  NOT NULL,
  payment_status  VARCHAR(20)  NOT NULL,
  payment_method  VARCHAR(20)  NULL,
  total_amount    INT          NOT NULL,
  cancel_reason   VARCHAR(100) NULL,
  canceled_by     VARCHAR(50)  NULL,
  canceled_at     DATETIME(6)  NULL,
  approved_by     VARCHAR(50)  NULL,
  approved_at     DATETIME(6)  NULL,
  refunded_by     VARCHAR(50)  NULL,
  refunded_at     DATETIME(6)  NULL,
  is_manual       BOOLEAN      NOT NULL DEFAULT FALSE,
  hidden          BOOLEAN      NOT NULL DEFAULT FALSE,       -- 취소 주문 삭제(주문현황 목록에서만 제외, 데이터·정산은 보존)
  created_at      DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_orders_seq (booth_id, business_date, order_seq),   -- 채번 중복의 물리적 차단
  UNIQUE KEY uq_orders_idempotency (idempotency_key),
  KEY idx_orders_search (booth_id, business_date, order_no),       -- 대시보드 주문번호 검색
  KEY idx_orders_session (session_id),                             -- 세션별 주문·미결제 집계 (엔티티 @Index, 문서 v1.3 미기재)
  CONSTRAINT fk_orders_booth   FOREIGN KEY (booth_id)   REFERENCES booth (id),
  CONSTRAINT fk_orders_session FOREIGN KEY (session_id) REFERENCES table_session (id),
  CONSTRAINT chk_orders_status         CHECK (status IN ('RECEIVED','DONE','CANCELED')),
  CONSTRAINT chk_orders_payment_status CHECK (payment_status IN ('UNPAID','PAID','REFUND_NEEDED','REFUNDED')),
  CONSTRAINT chk_orders_payment_method CHECK (payment_method IN ('BANK_TRANSFER','CASH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_item (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  order_id   BIGINT      NOT NULL,
  menu_id    BIGINT      NOT NULL,                          -- 참조용, FK 없음 (스냅샷이 본체)
  menu_name  VARCHAR(50) NOT NULL,
  unit_price INT         NOT NULL,
  qty        INT         NOT NULL,
  canceled   BOOLEAN     NOT NULL DEFAULT FALSE,            -- 항목 개별 취소 (엔티티 columnDefinition, 문서 v1.3 미기재)
  PRIMARY KEY (id),
  KEY idx_item_order (order_id),
  CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_counter (
  booth_id      BIGINT NOT NULL,
  business_date DATE   NOT NULL,
  last_seq      INT    NOT NULL DEFAULT 0,
  PRIMARY KEY (booth_id, business_date),
  CONSTRAINT fk_counter_booth FOREIGN KEY (booth_id) REFERENCES booth (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE staff_call (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  session_id BIGINT      NOT NULL,
  reason     VARCHAR(10) NOT NULL,
  acked      BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_call_session (session_id),
  CONSTRAINT fk_call_session FOREIGN KEY (session_id) REFERENCES table_session (id),
  CONSTRAINT chk_call_reason CHECK (reason IN ('HELP','WATER','ETC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE feedback (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  booth_id    BIGINT        NOT NULL,
  staff_id    BIGINT        NOT NULL,
  rating      INT           NOT NULL,
  easy_setup  BOOLEAN       NOT NULL,
  easy_orders BOOLEAN       NOT NULL,
  would_reuse BOOLEAN       NOT NULL,
  comment     VARCHAR(1000) NULL,
  created_at  DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_fb_booth FOREIGN KEY (booth_id) REFERENCES booth (id),
  CONSTRAINT fk_fb_staff FOREIGN KEY (staff_id) REFERENCES staff_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE booth_account_change_log (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  booth_id   BIGINT       NOT NULL,
  changed_by VARCHAR(50)  NOT NULL,
  changed_at DATETIME(6)  NOT NULL,
  old_value  VARCHAR(100) NOT NULL,
  new_value  VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_log_booth (booth_id),
  CONSTRAINT fk_log_booth FOREIGN KEY (booth_id) REFERENCES booth (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_map (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  image_url  VARCHAR(300) NOT NULL,
  width      INT          NOT NULL,
  height     INT          NOT NULL,
  updated_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
