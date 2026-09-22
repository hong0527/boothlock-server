# 부스락 API 명세서 v0.6 (노션 반영용)

> **기준 문서**: 노션 기능명세서 최종본 (2026-08 내보내기 CSV — Must/Should만 구현, Later는 부록에 경로만 예약). 기능 번호는 노션 병합본 체계(부스 탐색 1.7·1.8·1.9, 에러 알림 9.2, 로그인 8.1, 계정 8.2, 부스 설정 8.3)를 따른다.
> **v0.6 상태**: **통합 PR(브랜치 `integ/pos-backend`, HEAD f7aac1d)의 코드를 정본으로 삼아 문서를 코드에 맞춘 개정판**이다. v0.5까지는 "계약(합의)"이 앞서고 코드가 따라왔지만, 이번 개정은 반대로 통합된 코드가 실제로 하는 동작을 적었다. 코드로 확인하지 못한 문장은 본문에 **"확정 필요"** 로 표시했고 그 목록은 대조표(`scratchpad/reports/docs-v06.md`)에 있다.
> **용도**: 프론트·운영 준비의 기준. 상세 스키마의 원본은 Swagger(springdoc, `/v3/api-docs` — 운영 프로필은 기본 꺼짐)이고, 이 문서는 동작 규칙·에러·동시성 규칙까지 포함한 합의 기준이다.
> **노션 붙여넣기 팁**: 마크다운을 그대로 붙여넣으면 표·코드블록이 자동 변환됩니다. 대섹션(#)별로 토글 블록에 넣으면 탐색이 편합니다. 표 안에는 인용 블록을 넣지 않았다(v0.5 O17 표 깨짐 전례).

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.2 | 2026-07-30 | 초기 초안 |
| v0.3 | 2026-08-01 | 재고 API 삭제, 주문 상태 단순화, 직원 호출 추가, 웨이팅 부록 이동 |
| v0.4 | 2026-08-03 | 전 엔드포인트 상세화. 기능 번호·우선순위를 노션 최종본 기준으로 통일. 7.4를 운영자 피드백으로 정정 |
| v0.4.1 | 2026-08-03 | 1차 검증 반영: 주문번호 부스 영업일 통산 채번, 롤 3단 분리, REFUNDED, 영업일 정의, 계좌 감사, 세션 만료, 에러 코드 통일, 세션 = 테이블 단위 확정 |
| v0.4.2 | 2026-08-03 | 2차 재검증(3건) 반영 — 동결본: 채번 구현 지시 정정, 미결제 상한 8건 + 멱등키, SUPER_ADMIN의 `/admin/*` 금지, ADMIN 권한 필드 단위, 취소자 기록, businessDate 계산식, pwdAt, C2 tableToken 폴백 삭제, 환불 집계, QR 일괄 PDF |
| v0.4.3 | 2026-08-08 | DB 스키마 실행 검증 반영: 멱등키 unique 단일 컬럼, endedAtKey를 자기 id 기록 방식으로, §1.4에 501 표기 |
| v0.5 | 2026-09-09 | 2026-09-07 회의 결정 반영 — 부스 탐색(1.7·1.9) 파일럿 포함, 공개 축 `/api/v1/event/*` 신설(E1·E2), 운영자 POS형 메인(O3 좌표·O22·O10 `tableId`), O17 약도 좌표·카테고리, 단일 부스 → 복수 부스 |
| v0.5.1 | 2026-09-12 | E1 빈자리 판정을 세션 기준으로 전환, 유휴 임계 설정값(기본 3시간), E2 정적 서빙 구현 |
| v0.5.2 | 2026-09-14 | 기능 번호를 노션 병합본 체계로 되돌림, E1 서버 캐시 10초, 약도 서빙 가드, §7-21 실측 정정, O17 표 깨짐 정정 |
| **v0.6** | **2026-09-16** | **통합 PR 반영 — 코드가 정본.** ① 신규 절: **O23 항목 수량 변경·O23b 항목 개별 취소·O24 테이블 일괄 입금 확인·O25 테이블 1개 자동 추가·O26 테이블 삭제·O27 운영자 메뉴 목록**(main이 명세 밖에서 먼저 만든 API를 명세에 편입) ② 변경 절: O3(합집합 응답·`session.id`·유휴 세션 `session:null`·`needsCleanup`), O6(멱등 200·`{unpaidWarning,id,label,status,warning?}`), O10(`Authorization` 필수·`boothId` 400·`activeSessionOnly`·`businessDate` 기본=현재 영업일·`sessionId`·`itemId`), O11~O13 응답 형태 정정, O14(검증 순서·409), O15(JWT·`{callId,acked}`), O16/O17(category·mapX·mapY·**depositorName** — main #57 병합), O22(0~10000·반올림), C1(유휴 세션 재발급·응답 필드 정정), C2·O7·O8(`category`), C3(저장 직전 종료 410), C4·C5(취소 항목 제외·행 잠금), C6(`X-Session-Token`), E1(미결제 예외·삭제 테이블 제외) ③ **§1.2 유휴 만료를 SeatIdlePolicy 공용 정의로 통일**, §1.1·§7-21 무인증 전환 완료로 정정 ④ **미결제 정의 통일**(RECEIVED·DONE && UNPAID) ⑤ §7 신규: CORS, 잠금 뒤 읽기, 토큰 대조 ⑥ 확정 필요 표 갱신, §5 시더로 대체, §8 부록 갱신. 결제는 계좌이체만(PG 없음). 근거: 갈래 보고서 port-table·port-order·port-dash·port-fix·port-fix2·port-menu·port-cors·port-booth, verify-int2, e2e, deploy-mysql |
| v0.6.1 | 2026-09-20 | Figma 최종 디자인 확인 반영 — E1 좌석 표시를 3단계(여유/보통/만석)에서 **2단계(여유/혼잡)** 로 단순화. `empty/total ≥ 0.5 → 여유`, 그 외 0 포함 → 혼잡 |
| v0.6.2 | 2026-09-20 | Figma 최종 디자인 확인 반영 — 결제 안내 계좌 카드에 예금주 노출. C3·C4 `payment`에 `depositorName` 필드 추가(booth 설정값 그대로, `null` 가능). 기존 O16/O17 표의 "C3 결제 안내에는 나가지 않는다" 제약을 철회 |
| v0.6.3 | 2026-09-22 | O25 채번을 활성 테이블 기준으로 변경 — 테이블을 전부 지우고 다시 추가하면 `T-1`부터. soft delete된 같은 번호 행은 되살린다(토큰 유지). O26 soft delete도 번호 반납 |
| v0.6.4 | 2026-09-22 | O6 퇴실이 종료한 세션의 남은 접수(RECEIVED) 주문을 완료(DONE)로 넘긴다 — "결제 완료"·"테이블 비우기" 공통. 응답에 `completedOrderCount` 추가 |
| **v0.6.3** | **2026-09-20** | **팀 결정으로 "회원가입 API 없음"을 철회 — O0 신설.** `POST /api/v1/admin/auth/signup`이 부스+ADMIN 계정을 함께 만들고 O1과 같은 형태로 즉시 로그인 처리한다. **알려진 위험(§O0 경고 참고): 신원 확인 없이 임의의 boothName으로 계정 생성 가능** — 운영 배포 전 재검토 필요. Figma node `237:344`(회원가입 화면) 프론트 구현 포함 |
| v0.6.4 | 2026-09-20 | **O19 정산 CSV 구현 완료 — 마지막 501 스텁 해소.** O18과 같은 ADMIN 전용으로 확정. 행은 취소되지 않은 OrderItem 1건, 수식 주입 방지 적용. §7 부록·확정 필요 표·501 에러 설명에서 O19 관련 문구 정리 |
| v0.6.5 | 2026-09-23 | **O19 정산 CSV 조회를 "영업일 하루 고정"에서 사용자 지정 시간 범위로 확장.** `date`(영업일 단일 조회) 파라미터를 제거하고 `startAt`·`endAt`(둘 다 필수, KST, `datetime-local`)로 교체. 조회 기준은 그대로 주문 생성 시각(createdAt), `start <= createdAt < end` — 자정을 넘는 구간도 지원. **행 대상은 기존과 동일하게 결제 상태 무관 전체 원장**(UNPAID 포함, 개별 취소 OrderItem만 제외) — 이 부분은 동작 변경이 아니라 기존 동작의 재확인. CSV 마지막에 "총 결제완료 매출액" 요약(빈 줄 구분, `구분,금액` / `총 결제완료 매출액,{합계}`) 신규 추가 — PAID이면서 미취소인 상세 항목 금액만 합산, 상세 행과 같은 금액 값을 재사용해 중복 합산·불일치 방지. 프론트 `SettlementPage`도 날짜 1개 선택 → 시작/마감 일시 2개 입력으로 변경 |

## v0.6에서 확정이 필요한 항목 (팀 확인 후 이 절을 지운다)

v0.5의 9건 중 7건(부스 수 복수·좌석 표시 방식·좌표 입력 주체·홈 화면 담당·파트 분담·O3 합집합·category 값 체계·시딩 형식)은 코드로 확정됐다. 남은 것과 새로 생긴 것만 둔다 (7건).

| # | 항목 | 현재 값 (코드·설정) | 확정이 필요한 이유 |
|---|---|---|---|
| 1 | **파일럿 참여 부스 수·목록** | 시더가 JSON 파일의 부스 수만큼 넣는다 (§5) | 시딩 파일에 실제 부스명·계좌·좌표·ADMIN 계정 id를 채워야 한다. 부스가 정해지지 않으면 시딩 파일을 만들 수 없다 |
| 2 | **좌석 유휴 임계값** | 180분 (`boothlock.event.seat-idle-minutes`, 환경변수 `BOOTHLOCK_EVENT_SEATIDLEMINUTES`) | 축제 주점 평균 체류 시간을 아는 사람이 값을 정한다. 설정값이라 재배포 없이 바꾼다. C1·O3·E1이 같은 값을 쓴다 (§1.2) |
| 3 | **인원 선택·자릿세** | **보류 — 백엔드 없음.** 손님 프론트의 `/party-size` 화면은 서버를 호출하지 않고 로컬에만 저장한다 (e2e 실측) | 자릿세를 받으려면 주문 모델(항목이 아닌 정액)이 필요하다. 이번 파일럿은 하지 않는다는 결정만 기록 |
| 4 | **O17에서 category·mapX·mapY를 null로 지우기 허용 여부** | 코드는 **거부(400)** — 시딩 값을 폼 기본값 null로 날리는 사고 방지 | 운영 중 핀을 빼야 하는 상황이 있으면 별도 결정 필요. 현재는 시딩 파일 수정 + 재기동으로만 가능 |
| 5 | **O13 취소 사유 필수화** | 코드는 **선택** — 빈 값이면 `"운영자 취소"`로 기록 (운영자 프론트에 사유 입력 UI가 없음) | v0.4 명세는 "1~100자 필수"였다. 분쟁 방지용 기록이 획일화되는 것을 받아들일지 결정 |
| 6 | **전 영업일 미결제가 남은 세션의 운영 규칙** | 06:00 경계가 지나면 전날 미결제는 세션을 붙잡지 않는다. C1 재스캔으로 새 세션이 열리면 옛 미결제는 O3·O6·O24에서 빠지고 **O10 대시보드(해당 `businessDate`)에서만** 보인다 (verify-int2 §2) | 의도된 동작이나 운영자가 전날 미수금을 O10·정산으로 대사해야 한다. 운영 절차에 넣을지 결정 |
| 7 | **운영자 인증의 부스 클레임 검사 범위** | 대시보드 파트(O10~O15·O21·O23·O24)와 O16은 JWT `boothId` 클레임 ≠ 계정 현재 부스면 401. 테이블·메뉴·정산·O17 경로는 계정의 현재 부스로 스코프만 한다 | 보안 침해는 아니다(어느 경로도 남의 부스를 열지 않음). 하네스 하나로 통합할지 결정 (verify-int2 §4 Low) |

## 반영된 확정 결정 (이 명세서의 전제)

| # | 결정 | 명세 반영 |
|---|---|---|
| 1 | 재고 수량 관리 없음 — 품절 여부(soldOut)만 관리 (6.2) | 재고 API 없음. 품절은 O8 하나로 처리 |
| 2 | 주문 상태 단순화: 접수됨(자동) → 완료 + 취소 분기. 운영자 버튼은 입금확인·완료 2개 (5.2) | 상태 코드 RECEIVED / DONE / CANCELED |
| 3 | 조리/서빙 상태는 접수됨/완료 2단계로 축소 | 상태 모델 §2 |
| 4 | **결제는 계좌이체(+현금)만 — PG 없음** (3.2, 5.4). 운영자가 수동 승인 | 결제 상태는 주문 상태와 별도 축. `paymentMethod`는 BANK_TRANSFER / CASH |
| 5 | 소비자 화면 잔여 수량 숫자 미표기 (2.1) | 메뉴 응답에 수량 필드 없음 |
| 6 | 세션은 **테이블 단위** — 같은 QR을 찍은 일행은 같은 세션 공유 | C1 동작 규칙 |
| 7 | 웨이팅·PG·통계 고도화·중앙 관리자는 Later | §8 |
| 8 | 실시간성은 전부 **폴링** | 손님 5~12초, 운영자 3~5초 |
| 9 | 매출 집계는 **입금 확인 완료(PAID) 주문 기준** (7.1) | O18 |
| 10 | 7.4 피드백은 운영자가 부스락 서비스를 평가 | O20 |
| 11 | 8.2(부스 등록·계정 발급)는 **시더(`EventSeeder`)로 대체** — `/super/*` API는 만들지 않았다 | §5 |
| 12 | "입금 확인 후 조리"는 운영 규칙 — 서버 강제 없음. 위장 주문의 실질 방어선 (§7-1) | O12 |
| 13 | **(9/14·9/16) 결제창 '결제 완료' = O24 일괄 입금 확인 → O6 퇴실. '테이블 비우기' = O6만** | O6·O24 |
| 14 | **(9/16) 수량 증가 허용** — 단 입금 전(RECEIVED·UNPAID)·품절·숨김·주문 마감 검사를 C3과 같은 기준으로 한다 | O23 |
| 15 | **(9/16) 개별 취소한 항목은 행을 남기고 숨긴다**(`canceled=true`). 마지막 항목 취소는 O13과 같은 전이 | O23b |
| 16 | **(9/16) 미결제 정의 = `status ∈ {RECEIVED, DONE}` && `paymentStatus = UNPAID`** — 서빙 여부와 무관하게 입금이 안 된 주문. O3·O6·O24·유휴 예외·E1이 같은 정의를 쓴다. C3 상한·C5·O23 판정은 RECEIVED·UNPAID(다른 목적) | §2, §7-23 |
| 17 | 메뉴 분류는 **운영자 등록 시 지정**(MAIN / SIDE / DRINK), 분류 없음 허용. 통합 결과 main #53 구현을 채택 | C2·O7·O8·O27 |
| 18 | 손님 홈 화면 경로 `/home`. 프론트·API **도메인 분리 배포**, 운영 DB **RDS MySQL 8.0** | §7-22 CORS, 배포 문서 |

---

# 1. 공통 규약

## 1.1 기본

| 항목 | 규칙 |
|---|---|
| 프로토콜 | HTTPS 필수 — 리다이렉트·HSTS는 프록시/로드밸런서 단에서 처리한다 (앱에는 없음) |
| 형식 | REST + JSON (UTF-8), `Content-Type: application/json`. 불일치 시 `415 UNSUPPORTED_MEDIA_TYPE` |
| 경로 | 공개 `/api/v1/event/...` / 소비자 `/api/v1/...` / 부스 운영자 `/api/v1/admin/...`. 총관리자 `/api/v1/super/...`는 **예약만** (§5) |
| 날짜·시각 | ISO 8601, `+09:00` 오프셋 고정 (서버가 KST로 조립) |
| **영업일** | **06:00 ~ 익일 05:59 (Asia/Seoul)**. `businessDate = (해당 시각 − 6시간)의 날짜`. 주문 채번·O10 기본 조회·O18·유휴 정책의 "현재 영업일 미결제"가 전부 이 식(`OrderNumberingService.businessDateOf`)을 쓴다. 서버 코드는 JVM 기본 시간대에 기대지 않고 KST를 명시한다 |
| 금액 | 정수(원). 소수 없음 |
| ID | 서버 발급 정수 |
| 페이지네이션 | 없음. 단 O10은 상태 필터가 RECEIVED가 아닌 조회에 **최신 500건 상한** (O10 참조) |
| **부스 수** | **복수.** 부스·ADMIN 계정·약도는 시더로 넣는다(§5). 운영자 데이터는 JWT의 부스로 격리하고, 손님 데이터는 세션 토큰으로 격리한다 — **v0.5에서 미결이던 O10·O15·C6의 인증 전환은 완료됐다** (§7-21) |
| **CORS** | 프론트와 API를 다른 도메인에 두는 배포를 위해 `/api/**`·`/uploads/**`에 CORS 매핑이 있다. 허용 오리진은 설정값이며 `*`는 기동 거부 (§7-22) |

## 1.2 인증 — 4계층

| 대상 | 롤 | 방식 | 전달 | 발급 |
|---|---|---|---|---|
| 누구나 (홈 화면) | — | 없음 (공개) | — | — |
| 소비자 | — | 테이블 **세션 토큰** | 헤더 `X-Session-Token: {token}` | `POST /api/v1/table-sessions` (C1) |
| 부스 운영자 | `STAFF` / `ADMIN` | **JWT** | `Authorization: Bearer {token}` | `POST /api/v1/admin/auth/login` (O1) |
| 총관리자 | `SUPER_ADMIN` | JWT | 〃 | 계정은 시딩. `/admin/*` 호출 시 `403` |

**롤 규칙**
- `STAFF`: 자기 부스의 일반 운영 전부. O17의 `name`·`operatingHours`·`isOpen`·`category`·`mapX`·`mapY` 변경 가능
- `ADMIN`: STAFF 권한 + O17 `bankAccount` 변경 + O21 환불 완료 + **O18 매출 조회** (코드가 ADMIN만 허용한다 — v0.5의 "STAFF 이상"과 다르다)
- `SUPER_ADMIN`: `boothId = null`(무소속). `/admin/*` 호출 시 `403 FORBIDDEN` (`BoothInfoService.authenticate`가 ADMIN·STAFF 외 롤을 403으로 끊는다)
- 권한 필터는 경로 프리픽스로: `/event/* → 인증 없음`, `/admin/* → boothId 있는 JWT만`

**JWT 규칙 (`BoothJwtProvider`·`BoothInfoService`)**
- 클레임: `sub`=`staffId`, `staffId`, `boothId`(무소속이면 없음), `role`, `pwdAt`(비밀번호 변경 시각 epoch초). 만료 **43,200초(12시간)**
- 매 요청 검증: 서명·만료 → `staffId`·`sub` 일치 → 계정 존재·`active` → `pwdAt` == 계정 `passwordChangedAt` → `role` 클레임 == 계정 롤. 하나라도 어긋나면 `401 UNAUTHORIZED`. 비밀번호 재발급·정지가 기존 토큰에 즉시 반영된다
- **부스 클레임 대조**: 대시보드 파트 전 API(O10~O15·O21·O23·O23b·O24)와 O16은 `boothId` 클레임이 계정의 현재 부스와 다르면 `401`. 그 외 경로는 계정의 현재 부스로 스코프한다 (확정 필요 #7)
- 모든 `/admin/*` API는 JWT 부스 소속 데이터만 접근. 타 부스 리소스는 `404` (존재 은닉)

**공개 축(`/api/v1/event/*`) 규칙**
- 노출 가능: `boothId`·부스명·`category`·`isOpen`·테이블 수·빈 테이블 수·`mapX`·`mapY`·약도 주소·크기·갱신 시각. 그 외 필드는 추가하지 않는다 (§7-18)
- GET만. 개인정보 없음. 서버 캐시 10초 (E1)

**토큰 2종**

| 토큰 | 위치 | 수명 | 역할 |
|---|---|---|---|
| `tableToken` | QR 인쇄물 (`{boothlock.customer.base-url}/t/{tableToken}`) | O5 재발급·O26 완전 삭제 전까지 | 테이블 식별. CSPRNG 무작위값. 클라이언트는 세션 교환 후 보관하지 않음 |
| `sessionToken` | C1 응답 → 브라우저 저장 (쿠키 금지, 커스텀 헤더) | 아래 "세션 종료" 조건 중 먼저 오는 시점까지 | "이 테이블의 이번 이용" 식별. C2~C6 인증 |

**세션 활성·유휴·종료 규칙 (v0.6 — `SeatIdlePolicy` 공용 정의)**

C1 세션 복원, O3 `session`·`needsCleanup`, E1 빈자리 집계 **세 곳이 같은 클래스(`global/seat/SeatIdlePolicy`)의 정의를 쓴다.** 조건을 바꾸면 세 곳이 함께 바뀐다.

| 용어 | 정의 |
|---|---|
| **열린 세션** | `ended_at IS NULL` 그리고 `ended_at_key = 0` |
| **활동** | C1 복원, C2·C3·C4·C5·C6 인증 통과(`touchIfActive`), C3 저장(`touchIfSessionActive`) — 전부 `last_activity_at` 갱신 |
| **활성 세션** | 열린 세션이면서 ① `last_activity_at > 현재 − 유휴임계`(경계와 같은 시각은 유휴) **또는** ② **현재 영업일**에 접수된 **미결제(RECEIVED·DONE && UNPAID) 주문이 있다** |
| **유휴 세션** | 열린 세션인데 활성이 아닌 것. 유휴 임계 기본 180분(설정값, 확정 필요 #2) |
| **종료** | ① O6 퇴실 ② **C1 재스캔 시 유휴 세션이면 종료하고 새 세션 발급**(`restored:false`) ③ O26 삭제 전 검사 — 스케줄러는 없다. 유휴 세션은 누군가 재스캔하거나 퇴실 처리하기 전까지 열린 채 남는다 |

- 유휴 세션의 토큰은 **종료되기 전까지는 인증에 통과한다**(인증은 "열린 세션"만 본다). 손님이 다시 조회·주문하면 `last_activity_at`이 갱신돼 활성으로 돌아온다. 그 사이 다른 손님이 같은 QR을 찍으면 그 순간 종료되고 옛 토큰은 `410`
- 미결제 예외는 **현재 영업일 주문**만 세션을 붙잡는다. 06:00이 지나면 전날 미결제는 세션을 활성으로 만들지 않는다 (확정 필요 #6)
- 유휴 만료는 **세션만** 종료한다. 테이블 `status`는 그대로 OCCUPIED이고, O3는 `session:null`·`needsCleanup:true`로 "정리 필요"를 표시한다. O6를 누르면 EMPTY로 돌아간다
- 만료·종료된 토큰으로 호출 시 `410 SESSION_EXPIRED` → "QR을 다시 스캔해주세요" (C1이 새 세션 발급)

## 1.3 공통 에러 포맷

```json
{
  "error": {
    "code": "SOLD_OUT",
    "message": "품절된 메뉴가 포함되어 있습니다.",
    "details": [ { "menuId": 3, "menuName": "김치전" } ]
  }
}
```

- `code`: 클라이언트 분기용. `message`: 그대로 보여도 되는 한국어 문장. `details`: 선택 (SOLD_OUT은 메뉴 목록, CALL_COOLDOWN·LOGIN_LOCKED는 `{ "retryAfterSeconds": n }`)

## 1.4 에러 코드 전체 표 (`GlobalExceptionHandler` 기준)

| HTTP | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필드 누락·형식 오류·검증 실패, 본문 JSON 파싱 실패, 경로변수 타입 오류, 인증 헤더 외 필수 헤더 누락(`Idempotency-Key`), 업로드 5MB 초과. **존재하지 않거나 타 부스의 `menuId`**(요청 검증으로 취급). **폐기된 임시 파라미터**(O10 `boothId`, C6 `sessionId`)를 보낸 경우 |
| 401 | `UNAUTHORIZED` | `Authorization`·`X-Session-Token` 헤더 누락, JWT 서명·만료·클레임 불일치, 정지 계정, 비번 재발급으로 무효화된 JWT, 부스 클레임 ≠ 현재 부스(대시보드·O16) |
| 401 | `LOGIN_FAILED` | 아이디/비밀번호 불일치 (남은 시도 횟수 미노출) |
| 403 | `FORBIDDEN` | 롤 부족 — STAFF의 bankAccount 변경·O18·O21, SUPER_ADMIN의 `/admin/*` |
| 404 | `NOT_FOUND` | 리소스 없음. 타 부스·타 세션·**삭제(soft delete)된 테이블** 포함 (존재 은닉). 없는 경로 |
| 405 | `METHOD_NOT_ALLOWED` | 있는 경로에 잘못된 메서드 (보조 코드) |
| 409 | `SOLD_OUT` | 주문·수량 증가에 품절·숨김 메뉴 포함 (details에 메뉴 목록) |
| 409 | `ORDER_CLOSED` | 부스 `isOpen=false`에서 주문·수량 증가 시도. message `"지금은 주문을 받지 않습니다."` |
| 409 | `INVALID_STATE` | 불가능한 상태 전이(재취소·재완료·PAID 주문의 손님 취소·항목 수정), **메뉴명 중복**(O7·O8), 테이블 삭제 거부(O26), O24 대상 없음·합계 불일치, O14 퇴실 경합 |
| 409 | `ALREADY_PAID` | 이미 결제된 주문에 입금 확인 재시도 |
| 410 | `SESSION_EXPIRED` | 빈 토큰·미존재 토큰·종료된 세션 토큰. message `"세션이 만료되었습니다. 테이블 QR을 다시 스캔해주세요."` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type 불일치 (보조 코드) |
| 429 | `CALL_COOLDOWN` | 직원 호출 30초 내 재시도 (`details.retryAfterSeconds`) |
| 429 | `ORDER_RATE_LIMITED` | 세션당 미결제(RECEIVED·UNPAID) 주문 8건 초과. message `"미결제 주문이 많습니다. 입금 확인 후 추가 주문해주세요."` |
| 429 | `LOGIN_LOCKED` | 로그인 연속 실패 잠금 (`details.retryAfterSeconds`) |
| 500 | `INTERNAL_ERROR` | 서버 오류. 디스코드 웹훅 통보는 **미구현**(TODO) — 계좌 변경 웹훅만 구현됨 |
| 501 | `NOT_IMPLEMENTED` | 미구현 스텁 — v0.6.4부터 없음(O19 구현 완료) |

---

# 2. 주문·결제 상태 모델 (확정 — 기능 5.2)

```
[주문 생성 C3·O14] ──► RECEIVED (접수됨, 자동)
                        ├─ 운영자 [완료] O12 ──► DONE (종결)
                        └─ 취소 (C5·O13·O23b 마지막 항목) ──► CANCELED (사유·취소자 기록, 종결)

결제 상태 (별도 축):
  UNPAID ──(O11 개별 / O24 일괄 입금확인)──► PAID
  PAID 주문을 O13으로 취소 ──► REFUND_NEEDED ──(O21 환불완료, ADMIN)──► REFUNDED
```

| 규칙 | 내용 | 코드 |
|---|---|---|
| 소비자 취소 (C5) | `RECEIVED` **그리고** `UNPAID`일 때만. 주문 행을 잠근 뒤 판정 | `OrderEntity.canCancel`, `findByIdAndSessionIdForUpdate` |
| 운영자 취소 (O13) | CANCELED가 아니면 어느 단계든 가능. 조건부 UPDATE 한 문장에서 `PAID → REFUND_NEEDED`를 함께 처리 | `OrderRepository.cancelByStaff` |
| 항목 단위 수정 (O23·O23b) | `RECEIVED && UNPAID`일 때만. 마지막 항목 취소는 O13과 같은 UPDATE로 CANCELED 전이(사유 `"전체 항목 취소"`, 취소자 = 운영자 loginId, `totalAmount = 0`) | `DashboardOrderActionService.cancelItem` |
| 입금 확인 (O11·O24) | `UNPAID && status ≠ CANCELED` — **DONE·UNPAID도 확인 가능**(순서 무관 원칙) | `OrderRepository.markPaid` |
| 완료 (O12) | `RECEIVED`만 → DONE. 결제 여부 무관 | `markDone` |
| 오취소 복구 | 없음. O14 수기 주문으로 재입력 | — |
| 환불 | 전액만. 송금은 시스템 밖, O21로 REFUNDED. 미처리 목록 = O10 `paymentStatus=REFUND_NEEDED` | `markRefunded` |
| 매출 집계 | `PAID` 기준. REFUND_NEEDED/REFUNDED는 별도 집계 | O18 |

**"미결제"의 유일한 정의 (v0.6, `global/domain/UnpaidOrderRule`)**

| status \ paymentStatus | UNPAID | PAID | REFUND_NEEDED | REFUNDED |
|---|---|---|---|---|
| RECEIVED | **미결제** | × | × | × |
| DONE | **미결제** | × | × | × |
| CANCELED | × | × | × | × |

- 이 정의를 쓰는 곳: O3 `unpaidOrderCount`, O6 `warning`, O24 대상, 유휴 정책의 미결제 예외(C1·O3), E1 좌석 집계. 다섯 쿼리가 같은 JPQL 상수를 잇고 `UnpaidOrderRuleConsistencyTests`가 일치를 검사한다
- **이 정의가 아닌 것**: C3 상한(429)·C5·O23 판정은 `RECEIVED && UNPAID` — "접수 중이면서 미입금"이라는 다른 목적

**주문번호(orderNo) 채번**
- `{정규화된 테이블라벨}-{부스 영업일 통산번호}` — 예 `A3-17`. 정규화 = 하이픈·공백(전각 포함) 제거 + 대문자, 영숫자만, 6자 이내, 단독 `M` 금지
- 카운터는 **`daily_counter` 테이블(PK `booth_id, business_date`)을 `FOR UPDATE`로 잠그고 +1** (`DailyCounterEntity.nextSeq`). v0.4의 `booth_daily_counter` 표기는 이 테이블을 뜻한다
- `uq_orders_seq(booth_id, business_date, order_seq)`는 최후 방어선. 위반 시 최대 3회 재시도, 실패하면 500
- 종료된 세션이면 채번 전에 `410`으로 끊어 번호를 소모하지 않는다 (C3 규칙 참조)
- orderNo는 영업일 안에서만 유일. 테이블 미지정 수기 주문은 `M-{통산번호}`

---

# 3. 소비자·공개 API

> 인증: `X-Session-Token` (E1·E2·C1 제외). 헤더 누락은 `401`, 빈 값·모르는 토큰·종료된 세션은 전부 `410`(손님이 할 일은 어느 쪽이든 QR 재스캔). 폴링: 홈 화면 10~15초(프론트 12초), 주문 상태 5~10초. 메뉴판은 진입 시 1회 + 주문 직전 재조회 권장

## 3.1 목록

| # | Method | Path | 기능명세 | 우선순위 |
|---|---|---|---|---|
| E1 | GET | `/api/v1/event/booths` | 1.7 부스 목록 조회 (공개) | Must |
| E2 | GET | `/api/v1/event/map` | 1.9 행사장 배치도 (공개) | Must |
| C1 | POST | `/api/v1/table-sessions` | 0.1 QR 스캔 · 0.2 세션 복원 | Must |
| C2 | GET | `/api/v1/menus` | 2.1 모바일 메뉴판 | Must |
| C3 | POST | `/api/v1/orders` | 2.2 장바구니 검증 · 2.3 주문 | Must |
| C4 | GET | `/api/v1/orders` | 2.3 주문·상태 조회 (폴링) | Must |
| C5 | POST | `/api/v1/orders/{orderId}/cancel` | 2.5 소비자 주문 취소 | Should |
| C6 | POST | `/api/v1/calls` | 2.4 직원 호출 | Should |

> 장바구니(2.2)·인원 선택은 클라이언트 로컬 상태 — 서버 API 없음. 품절 재검증은 C3에서 수행.
> **손님 여정**: 대표 QR → 홈 화면 `/home`(E2 약도 + E1 자리 현황) → 부스로 이동 → 테이블 QR(C1) → 인원 선택(로컬) → 메뉴판(C2) → 주문(C3) → 상태 확인(C4)
>
> **QR은 두 종류다.**
>
> | QR | 붙는 곳 | 찍으면 | 서버 |
> |---|---|---|---|
> | 대표 QR | 축제장 입구·포스터 | 홈 화면 주소가 열린다 | 서버 작업 없음 — 토큰 없는 단순 링크 |
> | 테이블 QR | 각 테이블 | 그 테이블의 세션이 시작된다(C1) | `tableToken` 검증 |

## 홈 화면 구성 (E1·E2가 함께 쓰이는 방식)

- **주(主): 약도 위 부스 위치** — E2 + `booth.mapX·mapY`. 진입 시 1회
- **부(副): 부스별 자리 현황** — E1 목록, 폴링. 분류 칩(FOOD/CAFE/GOODS/ETC)은 `category` 파라미터
- 부스 상세 화면(1.8)은 만들지 않는다. 좌석 2단계(여유/혼잡) 변환은 클라이언트가 한다

---

## E1. GET /api/v1/event/booths — 부스 목록·좌석 현황 (기능 1.7 + 1.8의 좌석 표시)

> **인증 없음.**

**Query**

| 파라미터 | 예 | 설명 |
|---|---|---|
| category | `FOOD` | 카테고리 필터. 대소문자 무시. 비어 있으면 전체, 모르는 값이면 빈 배열(400 아님). 분류 없는 부스는 필터를 주면 제외 |

**Response 200**

```json
{
  "booths": [
    {
      "boothId": 1,
      "name": "컴공 주점",
      "category": "FOOD",
      "isOpen": true,
      "mapX": 3200,
      "mapY": 5400,
      "tables": { "total": 10, "empty": 3 }
    },
    {
      "boothId": 2,
      "name": "동아리 카페",
      "category": null,
      "isOpen": false,
      "mapX": null,
      "mapY": null,
      "tables": { "total": 6, "empty": 6 }
    }
  ]
}
```

**필드 규칙**
- `tables.total`: **활성(`active = true`) 테이블 수** — 삭제된 테이블은 제외 (O3·O16 `tableCount`와 같은 기준). `tables.empty`: 활성 세션이 붙어 있지 않은 활성 테이블 수. 서버는 숫자만 준다
- 화면 2단계 권장 임계값: `empty/total ≥ 0.5 → 여유`, 그 외(0 포함) → 혼잡
- `mapX`·`mapY`: 0~10000 상대 좌표. 둘 중 하나라도 없으면 **둘 다 `null`**로 나간다
- `isOpen`은 주문 접수 스위치(O17). 화면 문구는 "주문 마감"
- 정렬: `boothId` 오름차순

**빈자리 판정 기준 — §1.2 "활성 세션" 정의 그대로**

한 테이블이 "차 있다"로 세어지는 조건은 **활성 세션**이 붙어 있는 경우다: 열린 세션(`ended_at IS NULL`, `ended_at_key = 0`)이면서 `last_activity_at > 현재 − 유휴임계` **또는** 현재 영업일의 미결제(RECEIVED·DONE && UNPAID) 주문이 있다. 판정 기준(`SeatIdlePolicy.Criteria`)은 요청당 한 번 구해 모든 부스에 같은 값을 쓴다.

- 유휴 임계 기본 180분, `boothlock.event.seat-idle-minutes` / 환경변수 `BOOTHLOCK_EVENT_SEATIDLEMINUTES`. 0 이하는 기동 거부. 값 확정은 확정 필요 #2
- O6 퇴실은 `ended_at`을 찍어 **즉시** 빈자리가 된다. 퇴실을 누르지 않은 자리는 유휴 임계가 지나야 빈자리로 바뀐다 — 화면에 "현재 기준" 문구 권장
- 판정은 양쪽으로 틀릴 수 있다: 말없이 떠난 자리는 최대 임계 시간 동안 차 있게 보이고, 임계 시간 넘게 아무 활동 없이 앉아 있는(미결제도 없는) 손님의 자리는 빈자리로 보인다
- **v0.5.1의 "E1에는 미결제 예외가 없다" 서술은 해소됐다** — E1 쿼리의 세션 조인 조건에 같은 EXISTS가 들어가 C1·O3와 일치한다 (`SeatIdleConsistencyTests`)
- `status` 컬럼은 집계에 쓰지 않는다. O6가 구현돼 EMPTY로 되돌아오긴 하지만, 퇴실 누락 자리를 유휴로 걸러내려면 세션 기준이 맞다

**Errors**: 없음

**성능**: 서버 캐시 10초(`boothlock.event.booths-cache-seconds`, 0이면 끔). 전체 목록을 캐시하고 `category` 필터는 캐시 결과에서 건다. 좌석 집계는 부스·테이블·세션을 LEFT JOIN 해 GROUP BY 한 번. 세션 조인 조건은 `ON`에 둔다. 대가로 좌석 수·`isOpen`이 최대 10초 늦게 반영된다

---

## E2. GET /api/v1/event/map — 행사장 약도 (기능 1.9)

> **인증 없음.**

**Response 200**

```json
{
  "imageUrl": "/uploads/event/map.png",
  "width": 1600,
  "height": 1200,
  "updatedAt": "2026-09-10T14:00:00+09:00"
}
```

- `imageUrl`은 **API 서버 기준 상대 경로**다. 프론트가 다른 도메인에 있으면 API 도메인을 앞에 붙여야 한다 (e2e 실측: `VITE_API_BASE_URL` 적용)
- `width`·`height`: 원본 px. `pixelX = mapX / 10000 * 표시폭`
- 약도가 등록되지 않았으면 `404 NOT_FOUND` — 클라이언트는 목록만 보여준다. `event_map`은 `id DESC` 1건
- 서빙 규칙: `/uploads/event/**` 전용 핸들러. ① 폴더 설정 검증(마지막 폴더명 `event`, 작업 디렉터리·DB 파일 폴더 거부) ② `png`·`jpg`·`jpeg`·`webp`만, 숨김 파일·폴더 밖 심볼릭 링크 404 ③ `X-Content-Type-Options: nosniff` + `Content-Security-Policy: default-src 'none'; sandbox`
- 폴더 `boothlock.upload.event-dir`(기본 `data/uploads/event`), 메뉴 사진은 `/uploads/menu/`로 분리
- 등록은 시더가 한다(§5). 시더는 파일이 폴더에 실제로 있는지 확인하고 없으면 기동을 거부한다

**Errors**: `404`

---

## C1. POST /api/v1/table-sessions — 세션 발급 (QR 스캔 직후)

**인증**: 없음 (tableToken이 인증 수단)

**Request**: `{ "tableToken": "..." }` (필수, 공백 불가)

**Response 200**

```json
{
  "sessionToken": "sess_8f2k...",
  "booth": { "name": "컴공 주점", "isOpen": true },
  "table": { "label": "A-3" },
  "restored": true
}
```

| 필드 | 설명 |
|---|---|
| booth.name / table.label | 화면 상단 상시 표시. **v0.5의 `booth.id`·`table.id`는 응답에 없다**(`TableSessionResponse`) |
| booth.isOpen | false면 "주문 접수가 마감되었습니다" 안내 (메뉴판 열람은 가능) |
| restored | true = 활성 세션 복원, false = 새 세션 |

**Errors**: `400`(토큰 누락·공백) / `404 NOT_FOUND`(존재하지 않거나 재발급으로 폐기된 토큰, **삭제된 테이블의 토큰**, 대소문자·끝 공백만 다른 토큰 → "유효하지 않은 QR입니다.")

**동작 규칙 (`TableSessionService`·`TableSessionWriter`)**
1. 토큰으로 활성 테이블 조회 후 **`equals`로 바이트 단위 재대조** — DB 콜레이션이 대소문자를 무시하더라도 틀린 토큰이 통과하지 않는다 (§7-25)
2. 열린 세션이 §1.2 **활성**이면 활동 시각을 조건부 UPDATE로 갱신하고 그 토큰 반환(`restored:true`). 갱신 0건(그 사이 퇴실)이면 복원하지 않는다
3. 없거나 **유휴**면 테이블 행을 `FOR UPDATE`로 잠근 쓰기 경계에서 다시 판정 → 유휴 세션은 종료(`ended_at`, `ended_at_key = id`)하고 새 세션 생성(`restored:false`), 테이블 `OCCUPIED`. 앞 손님의 토큰은 그 순간 `410`
4. 동시 스캔은 테이블 행 락에서 줄을 서고, 유니크 제약 `uq_session_active` 위반이 나면 트랜잭션 밖에서 재조회해 승자 세션을 돌려준다
- 프론트: 교환 직후 주소창에서 tableToken 제거, 저장하지 않음 (§7-8)

## C2. GET /api/v1/menus — 메뉴판 (기능 2.1)

**Response 200**

```json
{
  "boothName": "컴공 주점",
  "isOpen": true,
  "menus": [
    {
      "id": 3,
      "name": "김치전",
      "price": 8000,
      "imageUrl": "/uploads/menu/3f2a....jpg",
      "description": "돼지고기·밀가루 함유",
      "soldOut": false,
      "category": "MAIN"
    }
  ]
}
```

**규칙**
- `visible = false` 메뉴 제외. 품절 메뉴는 포함하되 `soldOut: true`. `id` 오름차순
- **`category` (v0.6)**: `MAIN` / `SIDE` / `DRINK` / `null`. 키는 항상 있다. 분류 없는 메뉴는 손님 화면의 '전체' 탭에만 보인다
- `imageUrl`은 API 서버 기준 상대 경로 `/uploads/menu/{랜덤}.jpg`
- 잔여 수량 필드 없음. 이 조회도 세션 활동으로 기록된다

**Errors**: `401`(헤더 누락) / `410`

## C3. POST /api/v1/orders — 주문 생성 (기능 2.3)

**Request 헤더**: `X-Session-Token` 필수(누락 401), `Idempotency-Key: {UUID}` 필수(누락·공백 400, 64자 초과 400). 주문 시도마다 새 키, 네트워크 재시도는 같은 키

**Request Body**

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| items | array | ✅ | 1~20종. **같은 menuId 중복 시 400** |
| items[].menuId | int | ✅ | 해당 부스 메뉴. 미존재·타 부스는 `400` |
| items[].qty | int | ✅ | 1 ≤ qty ≤ 30 |

**Response 201 (새 주문) / 200 (멱등 재요청 — 기존 주문 그대로)**

```json
{
  "orderId": 101,
  "orderNo": "A3-17",
  "status": "RECEIVED",
  "paymentStatus": "UNPAID",
  "totalAmount": 21000,
  "items": [
    { "menuId": 3, "menuName": "김치전", "unitPrice": 8000, "qty": 2, "subtotal": 16000 },
    { "menuId": 5, "menuName": "제로콜라", "unitPrice": 5000, "qty": 1, "subtotal": 5000 }
  ],
  "payment": {
    "method": "BANK_TRANSFER",
    "bankAccount": "카카오뱅크 3333-01-1234567 (홍길동)",
    "depositorName": "홍길동",
    "depositorNameRule": "입금자명을 '이름+A3-17'로 입력해주세요 (예: 김철수A3-17)"
  },
  "createdAt": "2026-09-15T18:30:00+09:00"
}
```

**서버 검증 순서 (`OrderCreateService.create` → `OrderWriter.save`)**
1. 세션 인증 → `410`. 인증 자체가 활동 시각을 갱신한다
2. 요청 형식 → `400` (항목 없음·21종 이상·수량 범위·중복 메뉴·키 길이)
3. **멱등 재요청 판정** — 같은 키의 주문이 있으면 새 주문을 만들지 않고 **200**. `isOpen`·rate limit보다 먼저라서 이미 접수된 주문의 재요청이 마감 때문에 뒤집히지 않는다. 키는 전역 unique이므로 **다른 세션·부스의 키와 겹치면 `400`**("요청 키를 새로 생성해 다시 시도해주세요") — 정상 UUID는 겹치지 않는다
4. 부스 `isOpen` → `409 ORDER_CLOSED`
5. rate limit: 세션당 `RECEIVED && UNPAID` 8건 이상이면 `429 ORDER_RATE_LIMITED`
6. 메뉴 존재·노출·품절 → 미존재 `400`, 숨김·품절 포함 시 `409 SOLD_OUT`(details에 해당 메뉴 전체). 부분 주문 없음
7. 금액은 DB 가격으로 재계산. 이름·단가는 스냅샷
8. **저장 트랜잭션 안에서 세션 생존 재확인**(`touchIfSessionActive`) — 인증 뒤 저장 전에 퇴실(O6)이 커밋됐으면 **`410`**, 번호를 소모하지 않는다. 이 UPDATE가 세션 행을 잠가 동시 퇴실이 이 주문 뒤로 줄 선다
9. `daily_counter` 채번 → 저장. 유니크 충돌은 3회 재시도

**규칙**
- 멱등 재응답의 `items`는 **취소된 항목(O23b) 제외** — 합계와 항목 합이 일치
- `payment.bankAccount`·`payment.depositorName`은 부스 설정값 그대로(둘 다 booth 테이블 값, 조립하지 않음). `depositorName`은 부스가 등록하지 않았으면 `null` — 프론트는 이 경우 예금주 줄을 표시하지 않는다. `payment.method`는 항상 `BANK_TRANSFER`(현금은 운영자가 O11에서 기록)
- `createdAt`은 마이크로초 절삭 — 첫 응답과 멱등 재응답이 같은 값

## C4. GET /api/v1/orders — 내 주문 조회 (폴링 5~10초, 기능 2.3)

현재 세션의 주문 전체를 최신순(`createdAt desc, id desc`) 반환. 폴링도 세션 활동으로 인정

```json
{
  "orders": [
    {
      "orderId": 101,
      "orderNo": "A3-17",
      "status": "RECEIVED",
      "paymentStatus": "UNPAID",
      "totalAmount": 21000,
      "items": [ { "menuId": 3, "menuName": "김치전", "unitPrice": 8000, "qty": 2 } ],
      "payment": { "bankAccount": "...", "depositorName": "홍길동", "depositorNameRule": "..." },
      "canCancel": true,
      "createdAt": "2026-09-15T18:30:00+09:00"
    }
  ]
}
```

- `canCancel`: 서버 계산 (`RECEIVED && UNPAID`)
- 취소된 주문도 포함(`CANCELED`, 사유 미노출). **개별 취소된 항목은 `items`에서 제외**(v0.6) — 운영자가 결제창에서 항목을 빼면 손님 화면에서도 사라지고 `totalAmount`와 항목 합이 맞는다
- 퇴실·재스캔으로 세션이 바뀐 뒤에는 이전 세션의 주문이 보이지 않는다(세션 단위 조회)

**Errors**: `401` / `410`

## C5. POST /api/v1/orders/{orderId}/cancel — 소비자 취소 (기능 2.5)

**Request**: body 없음 → **Response 200**: 갱신된 주문 객체 (C4 단건 형태)

**동시성**: 주문 행을 `FOR UPDATE`로 잠근 뒤 판정한다. O11 입금 확인이 먼저 커밋되면 여기서 PAID를 보고 `409`, 취소가 먼저면 O11이 0건으로 `409`. "취소됐는데 PAID" 조합은 생기지 않는다(경합 40판 실측 0)

**Errors**: `404`(내 세션의 주문이 아님) / `409 INVALID_STATE`(PAID·DONE·CANCELED → "직원에게 요청해주세요" 안내)

## C6. POST /api/v1/calls — 직원 호출 (기능 2.4)

**인증 (v0.6)**: `X-Session-Token` 헤더로만 세션을 식별한다. 헤더 누락 `401`. 옛 임시 파라미터 `?sessionId=`를 보내면 **`400`**(헤더가 있으면 토큰 검증보다 먼저 — 무헤더 + `sessionId`는 401). 미존재·종료 토큰 `410`

**Request**

| 필드 | 타입 | 필수 | 값 |
|---|---|---|---|
| reason | string | ✅ | `HELP` / `WATER` / `ETC`. 누락·모르는 값 `400` |

**Response 201**: `{ "callId": 7, "reason": "HELP", "createdAt": "2026-09-15T18:31:00+09:00" }`

**규칙**
- 호출도 세션 활동으로 기록된다(인증 계층 `touchIfActive`)
- 세션 행을 `FOR UPDATE`로 잠근 뒤 `refresh`로 최신 상태를 다시 읽어 종료 여부를 판정한다 — 같은 요청에서 먼저 올라온 옛 사본이 방금 커밋된 퇴실을 가리지 않게 (§7-24)
- 같은 세션 30초 내 재호출 → `429 CALL_COOLDOWN`, `details.retryAfterSeconds`(남은 초). 사유가 달라도 같은 쿨다운

**Errors**: `400` / `401` / `410` / `429`

---

# 4. 운영자 API

> 인증: `Authorization: Bearer {JWT}`. 모든 데이터는 JWT 부스 소속만 — 타 부스·삭제 테이블은 `404`. 대시보드 폴링 3~5초(`GET /admin/orders` 하나로 주문 + 호출 수신).
>
> **운영자 메인 화면 (POS형)**: 배치도 = O3(좌표·세션·미결제 수) + O22(좌표 저장) + O25/O26(테이블 추가·삭제). 테이블 클릭 = 결제창: O10 `tableId&activeSessionOnly=true`로 지금 앉은 손님 주문만 조회 → 항목 수정(O23·O23b) → **'결제 완료' = O24 일괄 입금 확인 → O6 퇴실**, **'테이블 비우기' = O6만**. 테이블-홈 카드는 O3 `session.id`와 O10 `sessionId`를 대조해 현재 손님 주문을 고른다(O10을 테이블마다 따로 부르지 않기 위함).

## 4.1 목록

| # | Method | Path | 기능명세 | 우선순위 |
|---|---|---|---|---|
| **O0** | POST | `/api/v1/admin/auth/signup` | **8.1 운영진 회원가입 (v0.6.3 신설 — "회원가입 API 없음" 결정 철회)** | Should |
| O1 | POST | `/api/v1/admin/auth/login` | 8.1 운영진 로그인 | Must |
| O2 | POST | `/api/v1/admin/tables/bulk` | 4.6 테이블 일괄 등록 | Must |
| O3 | GET | `/api/v1/admin/tables` | 4.4 좌석 현황 (POS 배치도 소스) | Should |
| O4 | GET | `/api/v1/admin/tables/{tableId}/qr` | 4.6 QR 단건 다운로드 | Must |
| O4b | GET | `/api/v1/admin/tables/qr.pdf` | 4.6 전 테이블 QR 일괄 PDF | Must |
| O5 | POST | `/api/v1/admin/tables/{tableId}/regenerate-token` | 4.6 QR 재발급 | Must |
| O6 | POST | `/api/v1/admin/tables/{tableId}/checkout` | 4.4 퇴실·테이블 초기화 | Should |
| O7 | POST | `/api/v1/admin/menus` | 6.1 메뉴 등록 | Must |
| O8 | PATCH | `/api/v1/admin/menus/{menuId}` | 6.1 수정·숨김 / 6.2 품절 | Must |
| O9 | POST | `/api/v1/admin/uploads` | 6.1 메뉴 사진 업로드 | Must |
| O10 | GET | `/api/v1/admin/orders` | 5.1 실시간 대시보드 (폴링) | Must |
| O11 | PATCH | `/api/v1/admin/orders/{orderId}/payment` | 5.2·5.4 입금 확인 (개별) | Must |
| O12 | PATCH | `/api/v1/admin/orders/{orderId}/complete` | 5.2 완료 처리 | Must |
| O13 | POST | `/api/v1/admin/orders/{orderId}/cancel` | 5.5 운영자 취소 | Should |
| O14 | POST | `/api/v1/admin/orders` | 5.3 수기 주문 입력 | Should |
| O15 | PATCH | `/api/v1/admin/calls/{callId}/ack` | 2.4·5.1 호출 확인 | Should |
| O16 | GET | `/api/v1/admin/booth` | 8.3 부스 정보 조회 | Must |
| O17 | PATCH | `/api/v1/admin/booth` | 8.3 부스 정보 수정·접수 스위치 | Must |
| O18 | GET | `/api/v1/admin/stats/sales` | 7.1 실시간 매출 집계 (**ADMIN 전용**) | Should |
| O19 | GET | `/api/v1/admin/reports/settlement.csv` | 7.3 정산 CSV (**ADMIN 전용, v0.6.5 — startAt·endAt 구간, createdAt 기준**) | Should |
| O20 | POST | `/api/v1/admin/feedback` | 7.4 운영자 피드백 제출 | Should |
| O21 | POST | `/api/v1/admin/orders/{orderId}/refund-done` | 5.5 환불 송금 완료 처리 (**ADMIN 전용**) | Should |
| O22 | PATCH | `/api/v1/admin/tables/{tableId}/position` | 4.6 테이블 배치 좌표 저장 | Should |
| **O23** | PATCH | `/api/v1/admin/orders/{orderId}/items/{itemId}` | **5.3 보조 — 결제창 항목 수량 변경 (v0.6 편입)** | Should |
| **O23b** | POST | `/api/v1/admin/orders/{orderId}/items/{itemId}/cancel` | **5.5 보조 — 결제창 항목 개별 취소 (v0.6 편입)** | Should |
| **O24** | POST | `/api/v1/admin/orders/table-payment` | **5.4 테이블 일괄 입금 확인 (v0.6 신설, 9/14 결정)** | Must |
| **O25** | POST | `/api/v1/admin/tables` | **4.6 테이블 1개 자동 추가 (v0.6 편입)** | Should |
| **O26** | DELETE | `/api/v1/admin/tables/{tableId}` | **4.6 테이블 삭제 (v0.6 편입)** | Should |
| **O27** | GET | `/api/v1/admin/menus` | **6.1 운영자 메뉴 목록 (v0.6 편입)** | Must |

## O0. POST /api/v1/admin/auth/signup (기능 8.1, v0.6.3 신설)

**인증 없음.** 부스 + ADMIN 계정을 한 트랜잭션에서 함께 만들고, 성공하면 O1과 같은 형태로 바로 로그인 처리(JWT 발급)한다.

> **기본은 꺼져 있다.** 아래 위험 때문에 `boothlock.signup.enabled`(환경변수 `BOOTLOCK_SIGNUP_ENABLED`, 기본 `false`)로 감쌌다 — 평소에는 이 엔드포인트가 **`403 FORBIDDEN`**("회원가입 기능이 비활성화되어 있습니다.")만 돌려준다. 검증·DB 쓰기보다 먼저 잘리므로 꺼진 동안에는 부스도 계정도 생기지 않는다. 축제 운영 중에는 끈 채로 두고 계정은 시더로 만들며(§5), 시연·심사 때만 켠다. 화면 쪽은 `VITE_SIGNUP_ENABLED`로 따로 가려지지만 그건 노출 제어일 뿐 **실제 차단막은 서버 쪽 플래그다**.

**Request**: `{ "boothName": "...", "loginId": "...", "password": "..." }`

**Response 200**: O1과 동일한 `{ accessToken, expiresIn, staff }` 형태. `staff.role`은 항상 `ADMIN`

**규칙**
- `boothName` 1~50자, `loginId` 1~50자, `password` 8자 이상 — 아니면 `400 INVALID_REQUEST`
- `loginId` 중복이면 `409 INVALID_STATE`("이미 사용 중인 아이디입니다.") — 이때 방금 만든 부스 행도 롤백되어 고아로 남지 않는다
- 새 부스의 `bankAccount`는 계좌 미등록 안내 문구로 채워진다(`AccountPage.tsx`가 이 문구를 보고 미등록으로 판단) — 계좌는 로그인 후 O17로 등록
- `category`·`mapX`·`mapY`·`operatingHours`는 비워두고 시작(전부 O17로 나중에 채움)

> **⚠️ 알려진 위험 — 반드시 인지할 것**: 이 엔드포인트는 신원 확인 없이 누구나 임의의 `boothName`으로 ADMIN 계정을 만들 수 있다. 사업자등록이 없는 임시 축제 부스가 대상이라 "이 사람이 그 점포의 진짜 담당자인가"를 검증할 방법이 없고, 남의 점포명을 그대로 사칭해 손님이 QR로 주문·입금하게 만드는 사기가 이론상 가능하다. v0.6 이전에는 이 위험 때문에 "회원가입 API 없음, 계정은 시더로만 생성"이 명시적 결정이었다(§5) — v0.6.3에서 이 결정을 뒤집었으나 신원 확인 절차를 새로 만들지는 않았다. 운영 배포 전 재검토 필요.

## O1. POST /api/v1/admin/auth/login (기능 8.1)

**Request**: `{ "loginId": "...", "password": "..." }`

**Response 200**

```json
{
  "accessToken": "eyJ...",
  "expiresIn": 43200,
  "staff": { "role": "STAFF", "boothId": 1, "boothName": "컴공 주점" }
}
```

- `staff`에 **`id`는 없다**(`LoginDto.Staff(role, boothId, boothName)`). `SUPER_ADMIN`은 `boothId`·`boothName` null

**Errors**
- `401 LOGIN_FAILED`: 불일치 (남은 횟수 미노출)
- `429 LOGIN_LOCKED`: **5회째 실패부터** 30초 잠금, 이후 실패마다 2배(60·120·240·480), **최대 600초**. `details.retryAfterSeconds`(잠금 중 재시도는 남은 초 + 1). 성공 시 실패 카운터·잠금 초기화(`resetLoginFailures`). IP 단위 throttle은 **앱에 없음**(프록시 단 처리 전제)

**규칙**: 비밀번호 bcrypt 저장. **v0.6.3부터 O0 회원가입으로도 계정이 생긴다** — 시더(§5)는 여전히 파일럿 참여 부스를 미리 심는 용도로 남아있음. 시더로 만드는 계정의 `loginId`는 부스명에서 유추 불가한 값으로(O0로 셀프 등록하는 계정은 이 권고가 강제되지 않음)

## O2. POST /api/v1/admin/tables/bulk — 테이블 일괄 등록 (기능 4.6)

**Request** (둘 중 하나만)

```json
{ "count": 10, "labelPrefix": "A" }        // A-1 ~ A-10
{ "labels": ["A-1", "A-2", "B-1"] }
```

**Response 201**

```json
{ "tables": [ { "id": 12, "label": "A-1", "qrUrl": "/api/v1/admin/tables/12/qr" } ] }
```

- **`qrUrl`은 O4 다운로드 엔드포인트 링크**다(운영자 화면이 이미지를 받는 주소). QR 이미지 안에 들어가는 손님용 주소는 `{customer.base-url}/t/{tableToken}`이고 응답에는 노출하지 않는다
- 검증: 둘 다 있거나 둘 다 없으면 `400`. `count` 1~300, `labelPrefix` 필수. `labels` ≤ 300. 라벨 원본 20자 이하, **정규화(하이픈·공백 제거·대문자) 후 영숫자 1~6자, 단독 `M` 금지**, 부스 내 정규화 기준 중복 금지(삭제된 테이블 라벨 포함). 위반 `400 INVALID_REQUEST`
- 라벨은 원본(trim) 그대로 저장. 각 테이블에 CSPRNG `tableToken` 자동 발급

## O3. GET /api/v1/admin/tables — 좌석 현황 (기능 4.4)

```json
{
  "tables": [
    {
      "id": 12, "label": "A-3", "status": "OCCUPIED", "needsCleanup": false,
      "posX": 120, "posY": 240,
      "session": { "startedAt": "...", "lastActivityAt": "...", "id": 77 },
      "unpaidOrderCount": 1
    },
    { "id": 13, "label": "A-4", "status": "OCCUPIED", "needsCleanup": true,
      "posX": 260, "posY": 240, "session": null, "unpaidOrderCount": 0 },
    { "id": 14, "label": "A-5", "status": "EMPTY", "needsCleanup": false,
      "posX": null, "posY": null, "session": null, "unpaidOrderCount": 0 }
  ]
}
```

**v0.5 "명세 ↔ 구현 불일치"는 합집합으로 확정됐다.** `TableStatusResponse(id, label, status, needsCleanup, posX, posY, session, unpaidOrderCount)`, `Session(startedAt, lastActivityAt, id)`. O22 응답도 정확히 이 형태다.

| 필드 | 규칙 |
|---|---|
| 목록 범위 | **활성 테이블만**(삭제 제외). 라벨 자연 정렬(`A-2` < `A-10`) |
| `status` | `EMPTY` / `OCCUPIED`. C1 새 세션 시 OCCUPIED, O6 시 EMPTY |
| `session` | **§1.2 활성 세션일 때만** 싣는다. 열린 세션이 유휴(활동 없음·오늘 미결제 없음)면 `null` |
| `session.id` | 세션 PK (v0.6). O10 `OrderSummary.sessionId`와 대조해 "지금 앉은 손님" 주문을 고른다 |
| `needsCleanup` | `status = OCCUPIED && session == null` — 유휴 만료됐거나 세션이 정리된 테이블. 화면에 '정리 필요' + 퇴실 버튼 |
| `unpaidOrderCount` | **열린 세션(유휴 포함)의 미결제(RECEIVED·DONE && UNPAID) 주문 수.** 유휴로 `session:null`이어도 받을 돈이 남았으면 여기 잡힌다. 종료된 세션의 미결제는 세지 않는다(과거 건은 O10) |
| `posX`·`posY` | 배치도 px, 캔버스 좌상단 원점, 0~10000. 미배치 `null` |

- 조회는 테이블 수와 무관하게 고정 횟수(인증 1·테이블 1·열린 세션 1·미결제 1)
- **v0.5.1의 "E1과 O3가 같은 테이블을 다르게 말한다" 문제는 해소됐다** — 둘이 같은 `SeatIdlePolicy`를 쓴다

## O4 / O4b. QR 다운로드 (기능 4.6)

**O4 단건**: `GET /api/v1/admin/tables/{tableId}/qr?format=png|pdf` — `format` 생략 시 `png`. `Content-Disposition: attachment; filename=...` 포함(CORS로 노출됨). **Errors**: `404`(타 부스·미존재·삭제 테이블)
**O4b 일괄**: `GET /api/v1/admin/tables/qr.pdf` — **활성 테이블만**, 라벨 순, 페이지당 1테이블

- QR 내용: `{boothlock.customer.base-url}/t/{tableToken}` (환경변수 `BOOTLOCK_CUSTOMER_BASE_URL`). 이미지에 부스명·라벨·공식 도메인 문구 병기

## O5. POST /api/v1/admin/tables/{tableId}/regenerate-token — QR 재발급 (기능 4.6)

**Response 200**: `{ "id": 12, "label": "A-3", "qrUrl": "/api/v1/admin/tables/12/qr" }` / **Errors**: `404`(타 부스·미존재·삭제)

- 기존 `tableToken` 즉시 폐기 → 옛 QR은 C1에서 `404`. 진행 중 세션은 유지(손님 토큰 영향 없음). 손님 접근까지 끊으려면 O6 병행
- 테이블 엔티티는 변경 컬럼만 UPDATE(`@DynamicUpdate`)하므로 동시 첫 스캔(C1)이 새 토큰을 옛 값으로 되돌리지 않는다(경합 50판 실측 0)

## O6. POST /api/v1/admin/tables/{tableId}/checkout — 퇴실·테이블 초기화 (기능 4.4)

**Response 200 (항상 — 멱등)**

```json
{ "unpaidWarning": true, "id": 12, "label": "A-3", "status": "EMPTY", "completedOrderCount": 2, "warning": "미결제 주문 1건 있음" }
```

- `completedOrderCount`(int, 항상 있음)는 이 퇴실로 **완료(DONE) 처리한 남은 접수 주문 수**(v0.6.4, 아래 5번)
- `unpaidWarning`(boolean)은 기존 프론트가 읽는 필드. `warning`은 미결제가 있을 때만 있고 없으면 **필드 자체가 생략**된다(`@JsonInclude(NON_NULL)`)
- **열린 세션이 없어도 200**(v0.5 구현의 410은 폐기). '정리 필요' 테이블을 비우는 유일한 경로라 410이면 영영 비울 수 없다

**Errors**: `404`(타 부스·미존재·삭제 테이블)

**부작용·순서 (`TableAdminService.checkoutTable`)**
1. 테이블 행 `FOR UPDATE` — C1 세션 생성·O26 삭제가 같은 행을 먼저 잠그므로 퇴실·재스캔·연타가 직렬화된다
2. 열린 세션 id를 **잠금 읽기**로 확보 → `endOpenSessions` 조건부 UPDATE(`ended_at`, `ended_at_key = id`). 주문 저장 트랜잭션이 세션 행을 잠근 채 커밋 중이면 여기서 기다린다
3. **종료한 세션 기준으로** 미결제(RECEIVED·DONE && UNPAID) 건수를 잠금 읽기로 센다 — 집계를 먼저 하면 그 틈에 들어온 주문이 경고에서 빠진다. 이후 주문은 `410`
4. `status = EMPTY`
5. **종료한 세션의 RECEIVED 주문을 DONE으로** 조건부 UPDATE(O12와 같은 전이, v0.6.4) — 후결제 부스에서 손님이 나간다는 건 음식이 이미 나갔다는 뜻이라, 남은 접수 주문은 대개 "완료"를 깜빡한 것이다. 그대로 두면 주문현황 접수 탭(주방 대기열)에 떠난 손님 주문이 쌓인다. 입금 상태·취소·완료 주문·앞 손님 세션 주문은 건드리지 않는다. 잘못 넘어갔으면 주문현황 "되돌리기"로 되살린다
- 주문은 삭제하지 않는다(정산 보존). 미결제가 있어도 **막지 않는다** — 결제창 '결제 완료'는 O24를 먼저 부르고, '테이블 비우기'는 확인창 뒤 O6만 부른다
- v0.5의 "선택 구현 `checkout-bulk`"는 **구현하지 않았다**

## O7. POST /api/v1/admin/menus — 메뉴 등록 (기능 6.1)

**Request**

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| name | string | ✅ | trim 후 1~50자. **부스 내 중복 금지** → `409 INVALID_STATE`("동일한 메뉴명이 이미 존재합니다.") |
| price | int | ✅ | 정수, 0 이상 |
| description | string | — | trim 후 200자 이하. `null` 허용 |
| imageUrl | string | — | 500자 이하. O9 결과 |
| visible | boolean | — | 기본 true |
| soldOut | boolean | — | 보내면 타입만 검사하고 **저장값은 항상 false로 시작** |
| **category** | string | — | **`MAIN` / `SIDE` / `DRINK` 대문자 정확 일치(trim 없음), 또는 `null`/생략(분류 없음).** 그 외(`main`, `" MAIN"`, `""`, `ALL`) `400` — message `category: category는 MAIN, SIDE, DRINK 중 하나여야 합니다.` |

**Response 201**: `{ "id", "name", "price", "imageUrl", "description", "soldOut": false, "visible", "category" }`

- 검증 오류는 한 응답에 누적된다(`입력값이 올바르지 않습니다. name: ... price: ...`)

## O8. PATCH /api/v1/admin/menus/{menuId} — 수정·숨김·품절 (기능 6.1·6.2)

보낸 필드만 반영. 허용 필드 `name`·`price`·`description`·`imageUrl`·`visible`·`soldOut`·`category` — 그 외 필드는 `400`, 빈 본문 `400`

```json
{ "soldOut": true }       // 6.2 원클릭 품절 (해제는 false)
{ "visible": false }      // 6.1 숨김
{ "price": 9000 }
{ "category": "DRINK" }   // 분류 변경, null이면 지움(미분류), 생략하면 유지
```

**Response 200**: 갱신된 메뉴 객체(O7과 같은 형태) / **Errors**: `400` / `404`(타 부스·미존재) / `409 INVALID_STATE`(이름 중복)

- DELETE 없음 — `visible: false`. 가격 변경에도 기존 주문 금액 불변(스냅샷)
- 반영 즉시 손님 다음 조회에 노출. O23 수량 증가도 이 값을 다시 본다

## O27. GET /api/v1/admin/menus — 운영자 메뉴 목록 (기능 6.1, v0.6 편입)

**Response 200**: `{ "menus": [ O7 응답 형태 ... ] }` — **숨김·품절 포함 전체**, `id` 오름차순. 메뉴 등록/편집 화면 전용. **이력**: main이 명세 밖에서 먼저 만든 API. 응답에 `category`가 추가됐다

## O9. POST /api/v1/admin/uploads — 메뉴 사진 업로드 (기능 6.1)

- **Request**: `multipart/form-data`, 필드명 `file`. 최대 5MB(초과 400). jpg/png/webp — 매직바이트 검증, SVG 거부
- **Response 201**: `{ "url": "/uploads/menu/3f2a....jpg" }` + `X-Content-Type-Options: nosniff`. URL은 **API 서버 기준 상대 경로**
- 처리: 긴 변 1080px로 재인코딩(jpg) 저장, 파일명은 서버 생성 UUID, 서빙(`/uploads/menu/**`) 시 nosniff

## O10. GET /api/v1/admin/orders — 실시간 대시보드 (폴링 3~5초, 기능 5.1)

**인증 (v0.6)**: `Authorization` 필수 — 헤더가 없으면 `401`. 옛 임시 파라미터 **`?boothId=`를 보내면 `400`**("부스는 로그인 토큰으로 식별합니다.") — 헤더가 있으면 토큰 검증보다 먼저 거절하므로 잘못된 토큰 + `boothId`도 400이다

**Query**

| 파라미터 | 예 | 설명 |
|---|---|---|
| status | `RECEIVED` | 주문 상태 필터 (생략 시 전체) |
| paymentStatus | `UNPAID` | 결제 상태 필터. `REFUND_NEEDED` = 환불 미처리 목록 |
| businessDate | `2026-09-15` | 조회 영업일. **생략 시 현재 영업일(06:00 경계)** — v0.6 확정. 달력 날짜(`todayKst`)를 보내면 00~06시에 전날 영업일 주문이 빠지므로 프론트는 파라미터를 생략한다. 형식 오류 `400` |
| tableId | `12` | 그 테이블의 주문만 — 조회 영업일 범위의 **모든 세션**. 미존재·타 부스·**삭제 테이블은 `404`** |
| **activeSessionOnly** | `true` | **v0.6 신설.** `tableId`와 함께 true면 그 테이블의 **열린 세션**(`ended_at IS NULL`, `ended_at_key = 0`, 유휴 포함) 주문만 — 결제창이 "지금 앉은 손님" 주문만 보는 수단. 세션이 없으면 빈 목록 200. **`tableId` 없이 true는 `400`.** 기본 false. O24 대상 범위와 같은 세션 조건 |
| q | `A3-17` | 주문번호 **부분 검색**(`like %q%`). 조회 영업일 범위 안에서 |

**상한**: `status=RECEIVED` 조회는 무제한. 그 외(상태 필터 없음·DONE·CANCELED)는 **최신 500건**에서 조용히 잘린다 — 그 이전 건은 `q`·`businessDate`로 찾는다

**Response 200**

```json
{
  "orders": [
    {
      "orderId": 101, "orderNo": "A3-17", "tableLabel": "A-3",
      "status": "RECEIVED", "paymentStatus": "UNPAID", "paymentMethod": null,
      "manual": false, "totalAmount": 21000,
      "items": [ { "itemId": 501, "menuId": 3, "menuName": "김치전", "unitPrice": 8000, "qty": 2 } ],
      "canceledBy": null, "canceledAt": null, "cancelReason": null,
      "approvedBy": null, "approvedAt": null,
      "refundedBy": null, "refundedAt": null,
      "createdAt": "2026-09-15T18:30:00+09:00",
      "sessionId": 77
    }
  ],
  "calls": [
    { "callId": 7, "tableLabel": "B-1", "reason": "WATER", "createdAt": "..." }
  ]
}
```

- **필드명은 `manual`**(v0.5 예시의 `isManual`이 아님 — `OrderSummary` 레코드 컴포넌트명). `paymentMethod`는 입금 확인 후 채워진다
- `items[].itemId` (v0.6): O23·O23b의 경로 파라미터. **개별 취소된 항목은 `items`에서 제외**된다(합계와 일치)
- `sessionId` (v0.6): 주문이 붙은 세션 PK. 테이블 미지정 수기 주문은 `null`. O3 `session.id`와 대조
- 감사 필드(`canceledBy`·`approvedBy`·`refundedBy` = 운영자 loginId, 손님 취소는 `"CUSTOMER"`)가 응답에 그대로 있다. **O11·O12·O13·O21·O23·O23b·O24의 응답도 이 `OrderSummary` 형태**다
- `calls`: 미확인(`acked=false`) 호출만, **오래된 순**
- 정렬: `createdAt desc, id desc`
- `tableId` 필터는 `orders.session_id → table_session.table_id` 서브쿼리. 저장된 `tableLabel`은 표시용 스냅샷

## O11. PATCH /api/v1/admin/orders/{orderId}/payment — 입금 확인 (개별, 기능 5.2·5.4)

**Request**: `{ "method": "BANK_TRANSFER" }` (`BANK_TRANSFER` | `CASH`, 누락 400)

**Response 200**: 갱신된 주문 **`OrderSummary`**(O10 형태 — `paymentStatus: "PAID"`, `paymentMethod`, `approvedBy`, `approvedAt` 채워짐). v0.5의 `{orderId, paymentStatus, ...}` 축약 객체가 아니다

**규칙**: 조건부 UPDATE `WHERE payment_status = 'UNPAID' AND status <> 'CANCELED'` — **DONE·UNPAID도 확인 가능**. 승인자(loginId)·시각 자동 기록. 0건이면 잠금 재조회로 사유를 고른다

**Errors**: `409 ALREADY_PAID` / `409 INVALID_STATE`(취소된 주문) / `404`

## O12. PATCH /api/v1/admin/orders/{orderId}/complete — 완료 처리 (기능 5.2)

**Request**: body 없음 → **Response 200**: `OrderSummary`(`status: DONE`)

**Errors**: `404` / `409 INVALID_STATE`(RECEIVED가 아님 — 이미 DONE·CANCELED)
**규칙**: `UNPAID`여도 완료 가능. 완료된 미입금은 그대로 **미결제**(§2 정의)로 O3·O24·O6에 잡힌다

## O13. POST /api/v1/admin/orders/{orderId}/cancel — 운영자 취소 (기능 5.5, STAFF 가능)

**Request**: `{ "reason": "손님 요청" }` — **`reason`은 선택**(코드). 누락·빈 문자열이면 `"운영자 취소"`로 기록. 100자 초과 `400` (확정 필요 #5)

**Response 200**: `OrderSummary`(`status: CANCELED`, `canceledBy`=운영자 loginId, `canceledAt`, `cancelReason`. `PAID`였다면 `paymentStatus: REFUND_NEEDED`)

**Errors**: `400`(100자 초과) / `404` / `409 INVALID_STATE`(이미 취소)

**규칙**: RECEIVED·DONE 어느 단계든 가능. 조건부 UPDATE 한 문장에서 상태·사유·취소자·REFUND_NEEDED 전환을 처리해 동시 입금 확인과 겹쳐도 환불 대상이 누락되지 않는다

## O14. POST /api/v1/admin/orders — 수기 주문 입력 (기능 5.3)

**Request**

```json
{ "tableId": 12, "items": [ { "menuId": 3, "qty": 1 } ] }
```

**검증 순서 (v0.6 — "검증 먼저, 세션 나중")**
1. 인증 → `401`/`403`
2. `tableId`가 있으면 JWT 부스 소속·활성 테이블인지 → 아니면 `404` (미존재·타 부스·삭제 구분 없음, 부수효과 없음)
3. **사전 검증(`ManualOrderPreflight`)**: 요청 형식(1~20종·qty 1~30·중복·누락) `400` → 부스 `isOpen` `409 ORDER_CLOSED` → 메뉴 미존재·타 부스 `400` → 숨김·품절 `409 SOLD_OUT`. **여기서 거절되면 세션을 만들지 않고 테이블도 OCCUPIED가 되지 않는다**
4. 세션 확보 — 그 테이블의 QR 토큰으로 **C1 경로를 그대로** 탄다: 활성 세션이면 합류, 없거나 유휴면 새 세션 + OCCUPIED (손님 폰에서도 C4로 보임)
5. 저장(C3와 같은 `createManual`). 저장 직전 세션이 퇴실됐으면 **`409 INVALID_STATE`**("테이블이 퇴실 처리되어 주문을 붙일 수 없습니다. 다시 시도해주세요.") — 손님용 410을 운영자에게 주지 않고, 자동으로 새 세션을 만들어 재시도하지도 않는다

- `tableId` 생략 → 테이블 미지정, `orderNo = M-{통산번호}`, `sessionId = null`, 세션 부수효과 없음
- rate limit·멱등키 없음(운영자 화면은 버튼 비활성화로 더블탭 방지). `manual: true`
- 본문의 `boothId`는 무시된다(부스는 JWT)

**Response 201**: C3와 동일 형태 / **Errors**: `400` / `401` / `404` / `409 SOLD_OUT`·`ORDER_CLOSED`·`INVALID_STATE`

## O15. PATCH /api/v1/admin/calls/{callId}/ack — 호출 확인 (기능 2.4·5.1)

**인증 (v0.6)**: `Authorization` 필수(`401`). 호출 → 세션 → 테이블 → 부스로 스코프해 타 부스·미존재는 `404`

**Response 200**: `{ "callId": 7, "acked": true }` (v0.5의 빈 200에서 본문 추가). 멱등 — 이미 확인된 호출도 같은 응답. 이후 O10 `calls`에서 제외

**Errors**: `401` / `403` / `404`

## O16 / O17. GET·PATCH /api/v1/admin/booth — 부스 설정 (기능 8.3)

**GET Response 200**

```json
{
  "name": "컴공 주점", "bankAccount": "카카오뱅크 ...", "depositorName": "홍길동",
  "operatingHours": "18:00~02:00",
  "tableCount": 10, "isOpen": true,
  "category": "FOOD", "mapX": 3200, "mapY": 5400
}
```

**PATCH Request (부분 수정)** — 허용 필드 8개(`name`·`operatingHours`·`isOpen`·`bankAccount`·`depositorName`·`category`·`mapX`·`mapY`), 그 외 `400 지원하지 않는 필드입니다`, 빈 본문 `400`

| 필드 | 타입 | 변경 권한 | 규칙 |
|---|---|---|---|
| name | string | STAFF | 문자열, 공백 불가, 50자 이하 |
| operatingHours | string | STAFF | 50자 이하. `null` 허용(지움) |
| isOpen | boolean | STAFF | 주문 접수 스위치. false면 C3·O14·O23 증가가 `409 ORDER_CLOSED` |
| bankAccount | string | **ADMIN** | 공백 불가, 100자 이하. STAFF가 포함해 보내면 `403`(다른 필드만이면 통과) |
| **depositorName** | string | **ADMIN** | **예금주명 (v0.6 신설, main #57 / v0.6.2부터 손님 노출)** — 계좌 등록 화면 표시용 라벨. trim 후 50자 이하. `null`·빈 문자열·공백은 미지정(`null`)으로 저장. bankAccount와 같은 화면·같은 권한(STAFF `403`)이지만 입금 경로를 바꾸지 않는 표시값이라 **감사 로그·웹훅 대상은 아니다**. C3·C4 `payment.depositorName`으로 그대로 노출되어 손님이 입금 전 계좌 명의를 확인할 수 있다(`null`이면 프론트는 줄을 숨긴다) |
| **category** | string | STAFF | `FOOD` / `CAFE` / `GOODS` / `ETC` **대문자 정확 일치**. `null`·소문자·공백·그 외 값 `400` |
| **mapX / mapY** | int | STAFF | **둘을 함께** 보내야 한다(한쪽만 `400`). **정수만** 0~10000(소수·문자열·null `400`). null로 핀 제거는 받지 않는다(확정 필요 #4) |
| tableCount | int | 읽기 전용 | **활성 테이블 수**(삭제 제외) |

**Response 200**: GET과 같은 형태(전 필드) / **Errors**: `400` / `401` / `403` / `404`

- **v0.5의 "구현 주의(화이트리스트에 세 필드 없음)"는 해소됐다**
- **bankAccount 변경 규칙**: 같은 값이면 무변경. 바뀌면 `booth_account_change_log`(누가·언제·이전·새값) 기록 + 트랜잭션 커밋 후 웹훅(`BOOTLOCK_OPERATIONS_WEBHOOK_URL` 환경변수, 미설정이면 생략). 웹훅 본문은 이벤트 종류(`BOOTH_BANK_ACCOUNT_CHANGED`)·부스 id·변경자·시각만 담고 **계좌번호 자체를 보내지 않는다**(마스킹이 필요 없음)
- 부스 엔티티는 변경 컬럼만 UPDATE(`@DynamicUpdate`) — O17 저장이 동시 O25의 `next_table_seq`를 되돌리지 않는다

## O18. GET /api/v1/admin/stats/sales?date=2026-09-15 — 매출 집계 (기능 7.1, **ADMIN 전용**)

```json
{
  "totalSales": 1250000,
  "byMethod": { "BANK_TRANSFER": 1100000, "CASH": 150000 },
  "paidOrderCount": 87,
  "refundNeeded": { "count": 2, "amount": 29000 },
  "refunded": { "count": 1, "amount": 12000 }
}
```

- **응답에 `date` 필드는 없다**. 환불 집계는 `refundNeeded`·`refunded` 객체(v0.5의 평면 4필드가 아님)
- 집계 기준 `paymentStatus = PAID`. REFUND_NEEDED/REFUNDED는 별도. `date` 영업일 기준, 생략 시 현재 영업일. 형식 오류 `400`
- **STAFF는 `403`** (코드가 ADMIN만 허용 — v0.5 "STAFF 이상"과 다름)

## O19. GET /api/v1/admin/reports/settlement.csv?startAt=&endAt= — 정산 CSV (기능 7.3, **ADMIN 전용**, v0.6.5)

**Request**: `startAt`·`endAt` 둘 다 필수, ISO 8601 datetime(KST, 예 `2026-09-30T22:00:00`). 조회 구간은 `startAt <= createdAt < endAt`(주문 생성 시각 기준, 아래 참고). 자정을 넘는 구간(예 `2026-09-30T22:00` ~ `2026-10-01T03:00`)도 그대로 지원한다.

**Response 200**: `text/csv; charset=UTF-8` BOM 포함, `Content-Disposition: attachment; filename="settlement_{boothId}_{startAt}_{endAt}.csv"`(콜론은 파일명에 못 쓰므로 `yyyyMMdd'T'HHmmss` 형식). 행 = 구간 내 생성된 주문의 취소되지 않은 OrderItem 1건 — **결제 상태(UNPAID/PAID/REFUND_NEEDED/REFUNDED)와 무관하게 전부 나온다.** 컬럼 `주문번호, 테이블, 주문시각, 메뉴명, 수량, 단가, 금액, 주문상태, 결제상태, 결제수단, 승인자, 승인시각, 취소자, 취소시각, 취소사유, 환불처리자, 환불처리시각`(주문시각 컬럼은 createdAt — 이 컬럼이 곧 조회 범위 기준이기도 하다). `= + - @`로 시작하는 셀 값은 앞에 `'`를 붙여 수식 주입을 막는다(엑셀에서 셀 내용이 수식으로 실행되는 것 방지)

**요약 블록**: 상세 행 전체를 출력한 뒤 빈 줄 하나를 두고 다음 2행을 추가한다.

```
구분,금액
총 결제완료 매출액,{합계}
```

`{합계}`는 전체 원장 금액이 아니라, 상세 행 중 **`paymentStatus = PAID`이면서 개별 취소되지 않은 OrderItem**의 `단가 × 수량` 금액만 합산한 값이다. 상세 행 계산에 쓴 것과 같은 금액 값을 그대로 재사용하므로 상세-총액 불일치나 중복 합산이 생기지 않는다. **환불액이나 순매출을 의미하지 않는다** — 환불 여부와 무관하게 현재 `paymentStatus = PAID`인 항목의 금액만 더한 값이다.

**규칙**
- **대상 = 전체 원장**: `date`(영업일 단일 조회)로 UNPAID까지 포함한 하루치를 보던 v0.6.4까지의 동작을 그대로 이어받아, 결제 상태로 행을 거르지 않는다. "결제완료 매출만 보는 리포트"가 아니라 그 시간대에 실제로 어떤 주문이 있었는지 확인하는 원장이다(총 결제완료 매출액 요약만 PAID로 좁힌다)
- **구간 기준 = createdAt(주문 생성 시각)**: O18 영업일 집계·과거 O19의 `businessDate`와 달리 승인/입금 시각이 아니라 **주문이 생성된 시각**으로 범위를 정한다
- **경계**: `startAt <= createdAt < endAt` — start는 포함, end는 제외
- **취소 항목 제외**: 개별 취소(O23b)된 항목은 상세 행·총 결제완료 매출액 양쪽 모두에서 뺀다 — C4·대시보드 응답의 "취소 항목 제외" 관례와 통일
- **권한 = O18과 동일(ADMIN 전용)**: 명세 O19 자체엔 role 제한이 명시돼 있지 않았으나, 같은 "7 정산" 범주인 O18(매출 집계)이 ADMIN 전용이라 통일했다
- 삭제(hidden=true) 처리된 취소 주문도 원장에는 포함(O18과 같은 이유 — 정산은 숨김 여부와 무관하게 전부 봐야 함)
- **O18과의 관계**: O18은 영업일(생성 시각 기준, 06:00 경계)로 대상을 추린 뒤 PAID를 합산하고, O19는 사용자가 지정한 createdAt 구간의 전체 원장을 보여준다 — 조회 축이 달라 같은 날짜를 겨냥해도 O18 매출액과 O19 "총 결제완료 매출액"이 항상 일치하지는 않는다(구간 경계·PAID 전환 시점에 따라 서로 다른 쪽에 잡힐 수 있음)

**Errors**: `400`(`startAt`·`endAt` 누락, `startAt >= endAt`, 형식 오류) / `401` / `403`(STAFF)

## O20. POST /api/v1/admin/feedback — 운영자 피드백 (기능 7.4)

**Request**

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| rating | int | ✅ | 1~5 |
| easySetup / easyOrders / wouldReuse | boolean | ✅ | |
| comment | string | — | 최대 1000자 |

**Response 201**: 본문은 **생성된 id 정수 하나**(예: `3`) — v0.5의 `{ "feedbackId": 3 }` 객체가 아니다. 본문 검증(`400`)이 인증보다 먼저 일어난다(데이터 노출 없음)

## O21. POST /api/v1/admin/orders/{orderId}/refund-done — 환불 완료 처리 (기능 5.5 보조, **ADMIN 전용**)

**Request**: body 없음 → **Response 200**: `OrderSummary`(`paymentStatus: REFUNDED`, `refundedBy`, `refundedAt`)

**Errors**: `403`(STAFF) / `404` / `409 INVALID_STATE`(REFUND_NEEDED가 아님)

---

## O22. PATCH /api/v1/admin/tables/{tableId}/position — 테이블 배치 좌표 저장 (기능 4.6 보조)

**Request**: `{ "posX": 120, "posY": 240 }` — 둘 다 필수

**검증 (v0.6, `TableAdminService.validatePosition`)**

| 입력 | 결과 |
|---|---|
| 정수 0~10000 | 저장 |
| 소수 (예 `120.7`) | **HALF_UP 반올림**(→ 121) 후 0~10000이면 저장. 프론트 드래그 좌표가 소수로 와도 된다 |
| 음수 (`-0.4` 포함), 10000 초과(`10000.5` 포함), `1e400` | `400` |
| 문자열 `"120"`, 불리언, 배열, `null`, 필드 누락 | `400` — 클라이언트 버그를 조용히 묻지 않는다 |

**Response 200**: **O3의 테이블 항목과 정확히 같은 형태** 1건 / **Errors**: `400` / `401` / `404`(타 부스·미존재·삭제)

- 표시 전용. 겹침은 서버가 막지 않는다. 변경 컬럼만 UPDATE라 동시 C1·O5 값을 덮지 않는다

---

## O23. PATCH /api/v1/admin/orders/{orderId}/items/{itemId} — 결제창 항목 수량 변경 (v0.6 편입)

> **이력**: main PR #49가 결제 모달 "+/−" 버튼용으로 명세 밖에서 만들었다. 통합에서 주문 행 잠금과 증가 검사를 더해 계약(경로·본문)은 그대로 편입했다.

**Request**: `{ "qty": 3 }` — 정수, `@Min(1)`(0 이하 `400`)

**검증 순서 (`DashboardOrderActionService.updateItemQty`)**
1. 인증 → 주문을 부스 스코프로 **`FOR UPDATE`** 조회(없으면 `404`), 항목도 잠금 읽기
2. `RECEIVED && UNPAID`가 아니면 `409 INVALID_STATE` — 입금 후·완료 후·취소 후 수정 불가
3. 이 주문의 살아 있는 항목이 아니면(타 주문 항목 id·이미 취소된 항목) `404`
4. qty 1~30 밖 `400`
5. **증가(`qty > 현재`)일 때만** C3 5단계와 같은 검사: 부스 마감 `409 ORDER_CLOSED`, 메뉴 삭제 `400`, 숨김·품절 `409 SOLD_OUT`. 감소·동일 수량은 마감·품절 중에도 허용
6. 수량 갱신 → `totalAmount` 재계산(취소 항목 제외)

**Response 200**: `OrderSummary`

**동시성**: 주문 행 잠금이 O11·O13·O24의 조건부 UPDATE와 C5·다른 항목 수정을 직렬화한다. 입금이 먼저 커밋되면 여기서 PAID를 보고 409, 여기가 먼저면 입금 확인은 바뀐 금액을 본다 — "승인 금액 ≠ 저장 금액" 0건(경합 실측). 판정 재료는 잠금 읽기로만 읽는다 (§7-24)

## O23b. POST /api/v1/admin/orders/{orderId}/items/{itemId}/cancel — 결제창 항목 개별 취소 (v0.6 편입)

> **이력**: O23과 같은 PR에서 명세 밖에서 만든 API. 통합에서 잠금·마지막 항목 전이를 O13 경로로 통일했다.

**Request**: body 없음

**동작**
1. O23 1~3과 같은 잠금·상태(`RECEIVED && UNPAID`)·항목 검사
2. 항목을 **숨김**(`order_item.canceled = true`) — 행은 감사·정산용으로 남긴다. `totalAmount` 재계산
3. **남은 항목이 없으면** O13과 같은 조건부 UPDATE(`cancelByStaff`)로 주문 전체를 `CANCELED`: `cancelReason = "전체 항목 취소"`, `canceledBy` = 운영자 loginId, `totalAmount = 0`. UNPAID에서만 열리는 경로라 REFUND_NEEDED 금액이 0이 되는 일은 없다
4. 취소된 항목은 O10·C4·C3 멱등 재응답의 `items`에서 모두 사라진다

**Response 200**: `OrderSummary` / **Errors**: `404`(주문·항목) / `409 INVALID_STATE`

**동시성**: 같은 주문의 두 항목을 동시에 취소해도 행 잠금으로 직렬화되어 최종 `CANCELED`·`total 0`(40판 실측). C5·O13과 겹쳐도 취소 기록을 서로 덮어쓰지 않는다

## O24. POST /api/v1/admin/orders/table-payment — 테이블 일괄 입금 확인 (v0.6 신설, 기능 5.4)

> 결제창 '결제 완료' 버튼의 첫 호출. 성공하면 프론트가 이어서 O6를 부른다(9/14 결정). **이력**: 대시보드 갈래(D)에서 설계·구현, 통합에서 미결제 정의를 통일했다.

**Request**

```json
{ "tableId": 12, "expectedTotal": 25000, "method": "BANK_TRANSFER" }
```

| 필드 | 필수 | 규칙 |
|---|---|---|
| tableId | ✅ | JWT 부스 소속·활성 테이블. 아니면 `404` |
| expectedTotal | ✅ | 운영자 화면에 보였던 미결제 합계(0 이상 정수). 서버 재계산과 다르면 `409` |
| method | ✅ | `BANK_TRANSFER` / `CASH`. 그 외·누락 `400` |

**대상**: 그 테이블의 **열린 세션**(`ended_at IS NULL`, `ended_at_key = 0`, 유휴 포함)에 붙은 **미결제(RECEIVED·DONE && UNPAID)** 주문 전부. 종료된 세션·CANCELED·PAID·다른 테이블은 제외. O10 `tableId&activeSessionOnly=true`의 미결제 합과 같은 범위

**동작**: 대상을 **id 순으로 `FOR UPDATE`** 잠금 → 0건이면 `409 INVALID_STATE`("결제할 미결제 주문이 없습니다. 화면을 새로고침해주세요.") → 합계 ≠ `expectedTotal`이면 `409 INVALID_STATE`("미결제 합계가 바뀌었습니다 (화면 n원, 현재 m원)…") → 건별로 O11과 같은 조건부 UPDATE(`markPaid`, 승인자·시각 기록) → 한 건이라도 0행이면 **전체 롤백 후 `409`**

**Response 200**

```json
{ "orders": [ OrderSummary, ... ], "totalAmount": 25000 }
```

**Errors**: `400` / `401` / `403` / `404` / `409 INVALID_STATE`

**동시성**: 두 번 클릭(O24×O24)은 정확히 한쪽만 성공. O24×O11은 이중 승인 0. O24×O12는 두 축이 달라 둘 다 성공(DONE·PAID). O6가 먼저면 O24는 대상 0건 `409`이고 O6 응답의 `unpaidWarning`이 true. 서브쿼리의 세션 행은 잠그지 않아 O6·C1과 락 순서가 얽히지 않는다. 퇴실 후 남은 미결제는 O24 대상이 아니다 — O11 개별 확인만 가능

## O25. POST /api/v1/admin/tables — 테이블 1개 자동 추가 (v0.6 편입)

> **이력**: main PR #49의 "테이블 추가" 버튼용 명세 밖 API. 통합에서 채번 경합(H3·H4)을 고쳤다.

**Request**: body 없음 → **Response 201**: `{ "id": 15, "label": "T-4", "qrUrl": "/api/v1/admin/tables/15/qr" }`

**채번 규칙 (`TableAdminService.addSingleTable`)**
- 부스 행 `FOR UPDATE` + `refresh` 아래에서 부스의 테이블 행(삭제된 것 포함)을 잠금 읽기한다
- `seq = 활성 테이블의 "T-N" 라벨 최댓값 + 1` — **테이블을 전부 지우고 다시 추가하면 `T-1`부터 다시 시작**한다(v0.6.3). `next_table_seq`는 판정에 쓰지 않는다
- 같은 라벨 `T-seq`로 **soft delete된 행이 있으면 새 행을 넣지 않고 그 행을 되살린다**(`active=true`, `status=EMPTY`, 좌표 NULL, **토큰 유지** → 예전에 인쇄한 같은 번호 QR이 다시 동작). 과거 세션·주문은 그 행을 가리킨 채 남는다
- 정규화 라벨이 이미 있는 번호(예: O2로 만든 `T1`)는 건너뛴다 — 단 위의 되살릴 행은 건너뛰지 않는다
- 저장 후 `next_table_seq = seq + 1`을 **컬럼 단독 UPDATE**(기록용). 20건 동시 요청에서 라벨 20개 고유(실측)

**Errors**: `401` / `403`

## O26. DELETE /api/v1/admin/tables/{tableId} — 테이블 삭제 (v0.6 편입)

> **이력**: O25와 같은 PR의 명세 밖 API. §6 "삭제 없음" 원칙의 예외를 **이력 없는 마지막 테이블**로 한정했다.

**Response 204** (본문 없음)

**규칙 (`TableAdminService.deleteTable`)**

| 조건 | 결과 |
|---|---|
| 타 부스·미존재·**이미 삭제된** 테이블 | `404` |
| 열린 세션이 있음(사용 중) | `409 INVALID_STATE` "사용 중인 테이블은 삭제할 수 없습니다." |
| 활성 테이블 중 라벨 자연 정렬이 **마지막이 아님** | `409 INVALID_STATE` "마지막 번호의 테이블만 삭제할 수 있습니다." |
| 이용 이력(세션) **있음** | **soft delete** — `active = false`(과거 주문·세션 FK 보존). QR은 C1에서 `404`. 번호는 반납 — 다음 O25가 같은 번호로 이 행을 되살린다(같은 QR이 다시 동작) |
| 이용 이력 **없음** | **완전 삭제**(행 제거, QR 폐기). 번호는 반납 — 다음 O25가 같은 번호를 새 QR로 낸다. 인쇄한 QR은 무효가 되므로 재출력 필요 |

- 삭제된 테이블은 **모든 경로에서 없는 것으로 취급**: C1·O4·O5·O6·O22·O10 `tableId`·O14 `tableId`·O24 `404`, O3·E1·O4b·O16 `tableCount`에서 제외
- 잠금 순서: 부스 행 → 테이블 행 → 그 테이블의 세션 행(잠금 읽기) → 부스의 활성 테이블 행. 판정 재료는 전부 잠금 읽기(§7-24). 삭제 vs 첫 스캔 경합에서 "비활성 테이블에 열린 세션" 0건(100판 실측)

---

# 5. 총관리자 — 시더로 대체 (`/api/v1/super/*`는 예약만, 기능 8.2)

> **`/super/*` 컨트롤러는 만들지 않았다.** 부스 등록·부스별 ADMIN 계정·약도 등록은 기동 시 한 번 도는 **행사 시더(`seed/EventSeeder`)** 가 처리한다(확정 결정 #11). A1~A3 경로는 다부스 단계용으로 예약만 둔다.

| # | Method | Path (예약) | 기능 |
|---|---|---|---|
| A1 | POST | `/api/v1/super/booths` | 부스 등록 |
| A2 | POST | `/api/v1/super/staff` | 운영자 계정 발급 |
| A3 | PATCH | `/api/v1/super/staff/{staffId}` | 계정 정지·비번 재발급·잠금 해제 |

**시더 사용법**

| 항목 | 값 |
|---|---|
| 켜기 | `boothlock.seed.enabled=true` (환경변수 `BOOTLOCK_SEED_ENABLED`, 정확히 `true`일 때만) + `boothlock.seed.file`(`BOOTLOCK_SEED_FILE`)에 JSON 경로. 기본 꺼짐 |
| 파일 형식 | `booths[]`: `name`(50자 이하)·`bankAccount`·`category`(FOOD/CAFE/GOODS/ETC)·`mapX`·`mapY`(0~10000)·`operatingHours`(선택)·`admin{loginId, passwordEnv}`. `eventMap{imageUrl, width, height}`. 예시 `resources/seed/event-seed.example.json` |
| 비밀번호 | 파일에 적지 않는다. `passwordEnv`에 적은 **환경변수 이름**에서 읽는다(예 `BOOTLOCK_SEED_PASSWORD_CHANGE_ME_1`). 없으면 기동 실패 |
| 약도 | `imageUrl`의 파일이 `boothlock.upload.event-dir`에 **미리 있어야** 한다. 없으면 기동 실패 |
| 멱등 | 같은 이름의 부스·같은 loginId의 ADMIN·약도가 이미 있으면 건너뛴다(**기존 행은 수정하지 않는다**, 비밀번호도 재설정 안 함). 같은 loginId가 다른 부스나 STAFF면 실패 |
| 실패 | 자리표시자(`CHANGE_ME`) 그대로·필드 오타·환경변수 없음·약도 없음 등 어느 하나라도 틀리면 **서버를 띄우지 않고** 원인을 로그에 지목한다. 절반만 들어가는 일은 없다(트랜잭션) |
| 시딩 후 | 다시 끄는 것을 권장. 실제 시딩 파일은 저장소 밖(`backend/seed/*.json`은 ignore) |
| 하지 않는 것 | **테이블**(운영자가 O2·O25로 등록), **STAFF 계정**(현재 생성 수단 없음 — SQL 직접 삽입, 확정 필요), 계정 정지·비번 재발급(A3 미구현) |

---

# 6. 데이터 모델 (ERD 요약 — 상세는 DB 스키마 v1.4)

```
Booth 1─N Table 1─N TableSession(열린 세션 최대 1개) 1─N Order 1─N OrderItem N─1 Menu
Booth 1─N Menu / 1─N StaffAccount / 1─N Feedback / 1─N BoothAccountChangeLog / 1─N DailyCounter
EventMap (행사 약도 1건, 부스와 FK 없음)
TableSession 1─N Call
```

| 엔티티 | 핵심 필드 | v0.6 비고 |
|---|---|---|
| Booth | name, bankAccount, **depositorName**(null 가능), isOpen, operatingHours, category, mapX, mapY, **nextTableSeq** | `depositorName`은 예금주명 표시 라벨(감사 대상 아님). `nextTableSeq`는 O25 채번 카운터. `@DynamicUpdate` |
| Table | boothId, label(원본 ≤20자), tableToken(unique), status(EMPTY/OCCUPIED, **VARCHAR**), posX, posY, **active** | `active=false` = soft delete. `@DynamicUpdate` |
| TableSession | tableId, sessionToken(unique), startedAt, endedAt, lastActivityAt, endedAtKey | `unique(tableId, endedAtKey)`, 열린 세션 = `endedAt IS NULL AND endedAtKey = 0`. `id`가 O3·O10에 노출. `@DynamicUpdate` |
| Menu | boothId, name, price, imageUrl, description, soldOut, visible, **category** | **`unique(boothId, name)`**. category VARCHAR(MAIN/SIDE/DRINK/NULL) |
| Order | boothId, sessionId(null 가능), tableLabel(스냅샷), businessDate, orderSeq, orderNo, idempotencyKey(unique·null 가능), status, paymentStatus, paymentMethod, totalAmount, cancelReason, canceledBy/At, approvedBy/At, refundedBy/At, manual, createdAt | `unique(boothId, businessDate, orderSeq)`, **`index(session_id)`**. 열거형 VARCHAR. `@DynamicUpdate` |
| OrderItem | orderId, menuId, menuName(스냅샷), unitPrice(스냅샷), qty, **canceled** | 개별 취소는 행 유지 + `canceled=true` |
| DailyCounter | (boothId, businessDate) PK, lastSeq | FOR UPDATE 채번 |
| StaffAccount | boothId(null=SUPER_ADMIN), loginId(unique), passwordHash(72), passwordChangedAt, role, active, failedLoginCount, lockedUntil | |
| BoothAccountChangeLog | boothId, changedBy, changedAt, oldValue, newValue | |
| Call(staff_call) | sessionId, reason(HELP/WATER/ETC), acked, createdAt | |
| Feedback | boothId, staffId, rating, easySetup, easyOrders, wouldReuse, comment, createdAt | |
| EventMap | imageUrl, width, height, updatedAt | 1건, 시딩 |

- 삭제 정책: 메뉴는 숨김, 주문·세션은 상태 종결, 항목은 `canceled`. **테이블만 예외** — 이력 없는 마지막 테이블은 O26이 행을 지운다
- 에러 알림(9.2) 웹훅은 계좌 변경(O17)에만 구현됐고 500 통보는 미구현

---

# 7. 보안·비기능 체크리스트

| # | 항목 | 상태·근거 |
|---|---|---|
| 1 | "입금 확인 후 조리"는 보안 통제 — 위장 주문이 UNPAID로 남아 손실이 되지 않게 하는 유일한 방어선 | 운영 교육 |
| 2 | tableToken·sessionToken은 CSPRNG, URL-safe | `SecureTokenGenerator` |
| 3 | 계좌번호는 서버 등록값만 응답에 포함. 변경은 ADMIN + 감사 로그 + 커밋 후 웹훅 | O17 |
| 4 | 모든 `/admin/*`에서 리소스 부스 == JWT 부스. 본문·쿼리의 `tableId`(O10·O14·O24)도 `BoothTableLookup`으로 검증. SUPER_ADMIN은 403 | 보안 매트릭스 전 경로 통과(verify-int2 §4) |
| 5 | 타 부스·타 세션·삭제 테이블은 `404`. `403`은 롤 부족만. 본문 `menuId`는 `400` | §1.4 |
| 6 | 금액은 서버 재계산 — 요청에 금액 필드 없음. O24 `expectedTotal`은 확인용이지 저장값이 아니다 | C3·O14·O23·O24 |
| 7 | 로그인: 5회부터 지수 백오프 잠금(최대 600초), 남은 횟수 미노출. IP throttle은 프록시 단 | O1 |
| 8 | tableToken URL 노출 완화: 교환 후 주소창 제거, 미보관. `Referrer-Policy`·액세스 로그 마스킹은 프록시/프론트 몫 | C1 |
| 9 | **세션 유휴 정책은 `SeatIdlePolicy` 하나** — 활성 = 열린 세션 && (최근 활동 ‖ 현재 영업일 미결제). 만료 기록은 C1 재스캔·O6 시점. 세션만 종료, status는 O6로만 EMPTY | §1.2 |
| 10 | JWT는 매 요청 `active`·`pwdAt`·`role` 대조 — 정지·비번 재발급 즉시 반영 | `BoothInfoService.authenticate` |
| 11 | 테이블 QR = 그 테이블의 공용 접근권(의도된 설계). 유휴 세션은 재스캔 시 새 발급되어 앞 손님 주문이 새 손님에게 넘어가지 않는다 | C1 |
| 12 | 업로드: 매직바이트·SVG 거부·재인코딩·랜덤 파일명·nosniff. 약도 서빙은 확장자 화이트리스트 + CSP | O9·E2 |
| 13 | 개인정보: 서버는 소비자 개인정보 미저장. 입금자명 실명은 운영자 계좌 거래내역에 남음 — 행사 후 폐기 안내 | 운영 |
| 14 | 정산 CSV 수식 주입 방지 | O19 (v0.6.4 구현 완료, v0.6.5 — startAt·endAt 구간 기준) |
| 15 | 목표 동시 접속 수치·부하 테스트 — 미실시(운영 미결) | 비기능 |
| 16 | 백업: RDS 자동 백업. 오프라인 모드 기각 | 운영 |
| 17 | HSTS·HTTPS 리다이렉트는 프록시/로드밸런서 | 배포 문서 |
| 18 | 공개 축 노출 범위 고정 — `bankAccount`·매출·주문·토큰 절대 금지. 응답 DTO를 엔티티와 분리 | E1·E2 (응답 본문 검사 0건 누출) |
| 19 | 공개 축 부하 대비 — 캐시 10초 + GROUP BY 한 번 | E1 |
| 20 | 좌표 검증 — `mapX`·`mapY` 정수 0~10000, `posX`·`posY` 숫자 0~10000(반올림) | O17·O22 |
| 21 | **복수 부스 전환 선행 조건 — 완료.** O10은 JWT 부스로만 조회(`boothId` 파라미터 400), C6는 `X-Session-Token`(`sessionId` 파라미터 400), O15는 JWT + 부스 스코프 404. 잔여 무인증 API 없음 | 통합 PR (r4 닫힘) |
| **22** | **CORS (v0.6)** — `boothlock.cors.allowed-origins`(환경변수 `BOOTLOCK_CORS_ALLOWED_ORIGINS`, 쉼표 구분, 기본 `http://localhost:5173`). 경로 `/api/**`·`/uploads/**`, 메서드 GET·POST·PATCH·PUT·DELETE·OPTIONS, 허용 헤더 `Authorization`·`X-Session-Token`·`Idempotency-Key`·`Content-Type`, 노출 헤더 `Content-Disposition`·`Retry-After`, `allowCredentials=false`(쿠키 미사용), `maxAge` 3600. **`*`·패턴·끝 슬래시·경로 포함 값은 기동 거부.** 빈 값이면 CORS 끔(같은 도메인 서빙용) — 빈 목록을 그대로 등록하면 전부 허용으로 뒤집히므로 가드가 필수. 비허용 오리진은 preflight·실제 요청 모두 403. 컨트롤러에 `@CrossOrigin`을 붙이지 않는다(이 설정과 별개로 합쳐짐). 프론트는 `credentials: 'include'`를 쓰지 않고, `imageUrl`(상대 경로)에 API 도메인을 붙인다 | `CorsConfig` |
| **23** | **미결제 정의 통일 (v0.6)** — `UnpaidOrderRule`(RECEIVED·DONE && UNPAID) 하나를 O3·O6·O24·유휴 예외·E1 다섯 쿼리가 잇는다. 완료 처리된 미입금이 어느 화면에서도 빠지지 않게 | §2 |
| **24** | **잠금 뒤 읽기 원칙 (v0.6)** — 판정 재료(주문 상태·항목·열린 세션·미결제 수·카운터·마지막 테이블)는 **`FOR UPDATE` 잠금을 얻은 뒤 잠금 읽기로만** 읽는다. 일반 조회·지연 로딩은 MySQL REPEATABLE READ에서 트랜잭션 첫 SELECT(인증) 시점 스냅샷을 돌려줘 잠금을 기다리는 사이 커밋된 변경을 못 본다(MySQL 8.4 실측). 컬렉션 join fetch에는 Hibernate가 FOR UPDATE를 떼므로 항목은 네이티브 `select * ... for update`로 읽는다. 같은 요청에서 먼저 올라온 엔티티는 `refresh(PESSIMISTIC_WRITE)`로 다시 읽는다. **격리 수준 설정(운영 풀 READ COMMITTED)에 기대지 않는다** — H2·MySQL RR·MySQL RC 세 조건에서 645건 통과 | port-fix2 |
| **25** | **토큰 대소문자 대조 (v0.6)** — `tableToken`·`sessionToken`은 DB 조회 뒤 `equals`로 바이트 단위 재대조한다. DB 콜레이션이 `ai_ci`(Hibernate 자동 생성)면 대소문자만 다른 토큰이 조회되고, `utf8mb4_bin`이어도 끝 공백을 무시한다. 운영 스키마는 bin 콜레이션이 필수이고(DB 스키마 v1.4) 코드가 한 겹 더 막는다 | `TableSessionService`·`TableSessionAuthService` |
| **26** | **잠금 순서 (v0.6)** — C1·O6: 테이블 행 → 세션 행. O26: 부스 행 → 테이블 행 → 세션 행. O25: 부스 행. C3: 세션 행 UPDATE → 카운터 행 → orders. O23·O23b·C5·O24: 주문 행(O24는 id 순). 어느 경로도 역순으로 잡지 않는다. 데드락·lock wait timeout 0(MySQL 실측) | 갈래 보고서 |

---

# 8. 부록 — 파일럿 미구현·보류 항목

| 항목 | 상태 | 비고 |
|---|---|---|
| 웨이팅 1.1~1.6, 대기·좌석 운영 4.1~4.3 | Later | `POST /api/v1/waitings` 등 경로 예약 |
| 부스 상세 1.8 중 소개·메뉴 미리보기·대기 등록 | Later | 좌석 2단계만 E1에 포함 |
| 이용 시간 모니터링 4.5 | Later | O3 `session.lastActivityAt` 클라이언트 확장 |
| PG 결제 3.1 · 영수증 3.4 · 더치페이 3.3 | Later / 미채택 | **결제는 계좌이체·현금만**(확정) |
| **인원 선택·자릿세** | **보류** | 프론트 `/party-size`는 로컬 저장만. 서버 모델 없음 |
| **총관리자 API A1~A3** | **미구현** | 시더(§5)로 대체. STAFF 계정 생성 수단 없음 |
| O6 일괄 퇴실 `checkout-bulk` | 미구현 | 단건 반복 |
| 500 에러 웹훅 통보 9.2 | 미구현(TODO) | 계좌 변경 웹훅만 |
| `Retry-After` 응답 헤더 | 미구현 | 429는 `details.retryAfterSeconds`만. CORS 노출 목록에는 선반영 |
| 통계 고도화 7.2 · 정산·PG 설정 8.4 · 중앙 관리자 8.5 · 알림 실패 대응 9.1 | Later | |

---

# 9. 다음 단계

1. 이 문서를 노션에 반영 → 프론트·운영이 v0.6 기준으로 배선 확인(O24·O6 순서, `businessDate` 생략, `manual`·`sessionId`·`itemId` 필드명, `imageUrl` 절대 주소화)
2. 확정 필요 7건 결정 → 해당 절 갱신 후 동결
3. 운영 준비: 시딩 파일 작성(§5), RDS 스키마 선적용(`schema-mysql8.sql`, `ddl-auto=validate`), CORS 오리진·JWT 시크릿·고객 base-url 환경변수 — 절차는 `배포_운영절차.md`
4. 남은 백엔드 Low: 인증 하네스 통합(확정 필요 #7), 죽은 코드 정리
