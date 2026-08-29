# 부스락 BoothLock

축제 부스를 위한 QR 테이블오더. 태블릿도, POS도, 사업자등록도 없이 — 손님 폰과 운영자 노트북만으로 주문을 받는다.

## 왜 만들었나

대학 축제 주점의 주문 방식은 여전히 수기다. 직원이 테이블을 돌며 주문을 받아 적고, 주방에 소리로 전달하고, 계좌이체가 들어왔는지 은행 앱을 뒤져가며 확인한다. 피크 시간엔 주문이 누락되고, 입금과 주문이 안 맞아 실랑이가 나고, 마감 정산은 자정을 넘기기 일쑤다.

시중 테이블오더(티오더 등)가 이 문제를 해결해주지만, 대부분 상설 매장용이다. 사업자등록과 PG 가맹 심사가 전제고, 태블릿을 설치·회수하는 구독 모델이라 2~3일짜리 학생 부스는 고객이 될 수 없다.

부스락은 그 빈자리를 노린다:

- **가입 없음** — 손님은 테이블 QR을 찍는 순간이 곧 입장이다
- **하드웨어 없음** — 손님 폰이 키오스크 역할을 한다
- **사업자등록 없음** — 결제는 계좌이체·현금만 받고 운영자가 수동 확인한다. 이체는 주문번호를 입금자명에 넣어("김철수A3-17") 입금 대조를 초 단위로 줄이고, 현금은 직원이 받은 뒤 대시보드에서 같은 [입금확인] 버튼으로 처리한다
- **일회성 운영** — 30분 세팅, 행사 후 정산 CSV 뽑고 폐기. 구독도 약정도 없다

첫 파일럿은 창원대학교 축제를 목표로 한다.

## 주요 기능

> **현재 상태: API 스켈레톤 단계.** 아래는 개발 목표 기능이다. 지금은 전체 API 뼈대(28개 엔드포인트 스텁)와 공용 규약(에러 형식·상태 모델·예외 13종)까지 완성돼 있고, 파트별 구현이 진행 중이다.

**손님 (QR 스캔으로 진입)**

- 모바일 메뉴판 — 품절(SOLD OUT) 실시간 표시
- 장바구니·주문 — 주문 즉시 계좌번호와 입금 금액, 입금자명 규칙 안내
- 주문 현황은 접수 → 결제 확인 → 완료 순서로 자동 갱신된다
- 입금 전 주문 취소, 직원 호출

**운영자 (로그인으로 진입)**

- 실시간 주문 대시보드 — 신규 주문·직원 호출 알림, 주문번호 검색으로 입금 대조
- 버튼 두 개로 운영 — [입금확인], [완료]
- 메뉴 관리 — 등록·수정·원클릭 품절
- 테이블 관리 — 일괄 등록, QR 인쇄용 PDF, 유출 시 재발급, 퇴실 처리
- 정산은 입금 확인 기준으로 집계하고 CSV로 내려받는다

**파일럿에서 하지 않는 것** — 웨이팅·부스 탐색, 카드/간편결제(PG), 다중 부스 중앙 관리. 확장 자리는 잡아두되 구현하지 않는다.

## 주문 한 건의 흐름

```
손님: QR 스캔 → 메뉴 담기 → 주문
  └ 서버: 품절 재검증 → 금액 재계산 → 주문번호 발급(A3-17) → 계좌 안내 응답
손님: 은행 앱에서 이체 (입금자명 "이름+A3-17") 또는 현금    ← 결제는 시스템 밖
운영자: 대시보드에서 A3-17 검색 → 입금 확인 → [입금확인]  ← 이때부터 조리
운영자: 음식 전달 → [완료]
손님 화면: 폴링으로 "결제 확인 → 완료" 자동 반영
```

## 기술 스택

| 구분 | 선택 | 비고 |
|---|---|---|
| 백엔드 | Java 21 (LTS), Spring Boot 4.1, Spring Data JPA | Gradle 툴체인이 JDK 21을 자동 사용 |
| DB | MySQL 8 (운영) / H2 (로컬 개발) | JPA 코드는 동일. 전환 시 build.gradle에 MySQL 드라이버 추가 + 설정 변경 필요 |
| 실시간 | 폴링 (손님 5~10초, 대시보드 3~5초) | 파일럿 규모에 WebSocket 불필요 |
| 인프라 | AWS (행사 기간만 가동) | |

## 시작하기

```bash
git clone https://github.com/hong0527/boothlock-server.git
cd boothlock-server
./gradlew bootRun          # 또는 IntelliJ에서 BoothlockServerApplication 실행
```

JDK 21이 없어도 된다 — Gradle 툴체인이 처음 빌드할 때 자동으로 내려받는다.

- 서버: `http://localhost:8080` — 정상 확인: `curl http://localhost:8080/api/v1/menus` → `501` 응답이면 성공 (모든 API는 `/api/v1`로 시작)
- H2 콘솔: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/boothlock;MODE=MySQL`, 계정 `sa`, 비밀번호 없음)
- 아직 구현 전인 API를 호출하면 `501 NOT_IMPLEMENTED`가 온다. 스텁이 정상 동작 중이라는 뜻이다

## 프로젝트 구조

```
src/main/java/com/boothlock/boothlock_server/
├── BoothlockServerApplication.java     # 진입점
│
├── global/                             # 공용 (전원 공유 — 수정 시 팀 공지)
│   ├── error/                          #   ErrorResponse, GlobalExceptionHandler,
│   │                                   #   명세서 §1.4 에러 코드 전부에 대응하는 공용 예외 13종
│   │                                   #   + 스텁 전용 NotImplementedException — 새로 만들지 말고 골라 쓸 것
│   └── domain/                         #   OrderStatus, PaymentStatus (주문·결제 상태 — 두 축 독립)
│
│  # 파트별 폴더 — 자기 파트 폴더 안에서만 작업한다 (담당자가 스텁을 구현으로 교체)
├── order/                              # 주문 (홍화수)
├── dashboard/                          # 대시보드·결제 처리·호출 (김재원)
├── tableqr/                            # 테이블·QR·세션 (전형준)
├── menu/                               # 메뉴 (권희원)
├── settle/                             # 정산·통계 (백지연)
└── booth/                              # 로그인·부스 설정 (황대겸) — 파트 폴더 내부 규칙(전 파트 공통):
    ├── controller/                     #   HTTP 창구 (BoothController.java)
    ├── service/                        #   검증·계산 규칙 (첫 서비스 만들 때 폴더 생성)
    ├── repository/                     #   DB 조회·저장 (BoothRepository.java 등)
    ├── domain/                         #   엔티티·파트 전용 enum (BoothEntity.java 등)
    └── dto/                            #   요청·응답 record (첫 DTO 만들 때 폴더 생성)

src/main/resources/
└── application.properties              # 서버·DB 설정 (공용 — 수정 시 팀 공지)

src/test/java/com/boothlock/boothlock_server/
├── BoothlockServerApplicationTests.java   # 컨텍스트 기동 테스트 — CI가 매 PR마다 실행
└── booth/…                                # 파트 테스트는 자기 파트 패키지에 (예: booth/BoothDomainRepositoryTests)
```

각 스텁에는 담당 API의 명세서 ID와 핵심 규칙이 주석으로 달려 있다. `throw` 한 줄을 구현으로 바꾸는 것이 작업 단위다.

## 공통 개발 패턴

스텁을 실제 구현으로 바꿀 때 파일 서너 개가 한 세트다. 메뉴를 예로 들면:

```
menu/domain/MenuEntity.java          — @Entity. 테이블 구조(필드 = 컬럼). 정본은 docs/DB스키마_v1.2.md
menu/repository/MenuRepository.java  — JpaRepository<MenuEntity, Long> 상속 인터페이스. DB 조회·저장 담당
menu/service/MenuService.java        — 검증·계산 등 규칙 처리. 컨트롤러는 얇게, 로직은 여기로
menu/controller/MenuController.java  — 요청을 받아 서비스에 넘기고 응답을 만든다 (스텁이 이미 있음)
menu/dto/MenuCreateRequest.java 등   — 요청·응답 record
```

- 엔티티에는 `@Table(name = "...")`로 스키마 문서의 테이블명을 그대로 명시한다 (예약어 사고 방지)
- enum은 `@Enumerated(EnumType.STRING)`으로 저장한다
- 에러는 공용 예외(`InvalidRequestException` 등)를 던지면 `GlobalExceptionHandler`가 정해진 형식으로 응답한다
- 단, **Order 엔티티는 예외** — 4개 파트가 함께 쓰는 교차점이라 홍화수가 전체 필드를 넣어 만들어 둔다 (CONTRIBUTING 0번 참조)

## 파트 분담

| 담당 | 파트 | API (명세서) | 시작 파일 |
|---|---|---|---|
| 홍화수 | 주문 | C3 · C4 · C5 | OrderController.java |
| 김재원 | 대시보드·결제 처리 | O10~O15 · O21 · C6 | DashboardController.java |
| 전형준 | 테이블·QR·세션 | C1 · O2~O6 · O4b | TableController.java |
| 권희원 | 메뉴 | C2 · O7~O9 | MenuController.java |
| 백지연 | 정산·통계·피드백 | O18~O20 | SettleController.java |
| 황대겸 | 계정·부스 설정 | O1 · O16 · O17 | BoothController.java |

## 협업 방식

- main은 항상 동작하는 상태를 유지한다. 팀원의 직접 push는 시스템이 차단하며, 브랜치 → PR → 리뷰 승인 1명 → Squash 머지로만 반영한다. 관리자(리드)는 세팅·긴급 수정에 한해 승인 없이 머지할 수 있으나, 이 경우에도 PR은 반드시 만든다 (CONTRIBUTING 0번)
- PR마다 CI가 `./gradlew build`를 자동 실행한다 — 빌드가 깨지면 머지하지 않는다
- 브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)에 있다. AI로 작업할 경우 AI에게 이 파일을 먼저 읽히고 시작한다
- 구현 기준은 항상 API 명세서(노션)다. 코드와 명세서가 다르면 명세서가 이긴다

## 문서

| 문서 | 위치 |
|---|---|
| 기능명세서 / API 명세서 v0.4.2 | 팀 노션 |
| **DB 스키마 v1.2** (테이블 11개·설계 원칙 — 엔티티 만들 때 정본) | [docs/DB스키마_v1.2.md](docs/DB스키마_v1.2.md) + 팀 노션 |
| 협업 규칙 | [CONTRIBUTING.md](CONTRIBUTING.md) |
| 회의록 | 팀 노션 (매주 토요일) |
