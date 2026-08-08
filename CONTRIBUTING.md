# 부스락 협업 규칙 v1.2

> 이 문서는 사람과 AI가 함께 따르는 작업 절차서다.
> **AI로 작업할 때는 작업 시작 전에 AI에게 이렇게 지시한다 (자기 이름·파트를 반드시 채울 것 — AI가 "내 담당 파일" 규칙을 지키려면 필요하다):**
> `"나는 {이름}({파트} 파트 담당)이다. 이 레포의 CONTRIBUTING.md를 읽고, 거기 적힌 절차와 금지 규칙을 그대로 지켜서 작업해줘."`

---

## 0. 절대 규칙 (위반 금지 — AI도 사람도 예외 없음)

1. **main에 직접 push 금지** — 모든 변경은 브랜치 → PR로만 올린다
   - 팀원: 리뷰 승인 1명을 받아야 머지된다 (시스템 강제)
   - 관리자(리드): 세팅·긴급 수정에 한해 승인 없이 머지(바이패스)할 수 있다. **단 이 경우에도 PR은 반드시 만들고, 본문에 바이패스 사유를 한 줄 남긴다** — 직접 push는 관리자에게도 금지
2. **force push 금지** (`git push -f`, `--force-with-lease` 전부) — 충돌은 1-1번, 실수는 1-2번 절차로 해결한다 (rebase 금지)
3. **남의 파트 파일 수정 금지** — 내 담당 컨트롤러/서비스/엔티티만 수정한다. 남의 파트에 문제를 발견하면 고치지 말고 담당자에게 알린다
   - 엔티티·리포지토리·서비스는 **최초 작성자 소유**. 다른 파트가 그 파일을 고쳐야 하면(예: 결제 처리가 주문 엔티티에 필드 추가, 수기 주문이 OrderService 재사용) 채팅 공지 후 PR을 올리고, 리뷰어로 그 파일 담당자를 지정한다
   - **Order 엔티티는 4개 파트(주문·대시보드·테이블·정산)가 함께 쓰는 교차점** — 홍화수가 DB 스키마 문서의 전체 필드(approved/canceled/refunded_by·at, payment_method, is_manual 포함)를 처음부터 넣어 만들고 잠근다. 이후 필드 추가는 위 절차를 따른다
   - 착수 순서: **홍화수의 개발 첫 PR이 Order·OrderItem 엔티티**다(다른 파트의 선행 조건). 그 머지 전까지 주문을 읽는 파트(김재원 O11~·백지연 O18~)는 엔티티와 무관한 작업(화면, DTO, 자기 엔티티)부터 진행한다
4. **공용 파일**(아래 6번 목록) 수정은 팀 채팅 사전 공지 후에만
5. **비밀값 커밋 금지** — 실제 계좌번호, 비밀번호, API 키, `.env`, `data/`(로컬 DB) 등
6. **PR 없이 하루 넘게 브랜치를 묵히지 않는다** (= 늦어도 다음 날엔 PR을 올린다. 머지 기한은 2번의 "당일~2일") — 작업 단위를 작게 잡을 것

## 1. 작업 사이클 — 이 순서와 커맨드 그대로

```bash
# [0] 시작 전: main 최신화 (항상 최신 main에서 브랜치를 딴다)
git checkout main
git pull origin main

# [1] 브랜치 생성 — 작업 "하나"당 브랜치 "하나" (이름 규칙은 3번)
git checkout -b feat/menu-crud      # ← 예시 값(메뉴 파트). 자기 파트의 {타입}/{파트}-{내용}으로 바꿔 쓴다

# [2] 작업 — 구현 후 반드시 본인이 직접 실행·확인
./gradlew bootRun   # 서버 실행 (터미널을 점유한다 — 확인 끝나면 Ctrl+C로 종료)
# 새 터미널에서 예:  curl -s http://localhost:8080/api/v1/menus   (구현 전이면 501이 정상)

# [3] 커밋 — 의미 있는 단위마다 (이름 규칙은 4번)
git add src/main/java/com/boothlock/boothlock_server/MenuController.java   # git add . 보다 파일 지정을 권장 (엉뚱한 파일 방지)
git commit -m "feat: 메뉴 등록 API 구현"

# [4] 올리기
git push -u origin feat/menu-crud

# [5] PR 생성 — GitHub 웹에서 생성 (템플릿 자동 적용) 또는:
gh pr create --web    # 브라우저가 열리며 템플릿이 자동 적용된다. --body로 본문을 넘기면 템플릿이 무시되므로 쓰지 않는다
# AI(브라우저 없음)의 경우: 템플릿 4칸을 채운 본문 파일을 만들어 gh pr create --title "..." --body-file 본문.md
# AI는 bootRun을 백그라운드로 실행하고, 확인이 끝나면 반드시 프로세스를 종료해 8080을 비운다

# [6] 리뷰어 지정 — PR 화면 오른쪽 Reviewers에서 5번 표의 버디 선택 (또는 gh pr edit --add-reviewer 아이디)
#     버디 승인 + CI(build) 통과 후 → "Squash and merge" 버튼으로 머지

# [7] 정리
git checkout main && git pull origin main
git branch -D feat/menu-crud        # 로컬 브랜치 삭제. Squash 머지는 커밋을 새로 만들어서 -d는 "not fully merged" 오류가 난다 — 머지 확인 후 -D 사용 (원격 브랜치는 머지 시 자동 삭제)
```

## 1-1. 충돌이 났을 때

PR 화면에 "This branch has conflicts" 가 뜨면:

```bash
git checkout feat/내브랜치
git pull --no-edit origin main   # main을 내 브랜치로 가져와 합친다 (rebase 금지, --no-edit는 편집기 안 뜨게)
git status                       # "both modified"로 표시된 파일이 충돌 파일
# 충돌 파일을 열어 <<<<<<< ======= >>>>>>> 표시를 정리하고 저장
git add 정리한파일 && git commit --no-edit
git push
```

- 꼬여서 처음부터 다시 하고 싶으면: `git merge --abort` (합치기 전 상태로 복귀)
- 같은 공용 파일에서 충돌이 반복되면 팀 채팅에서 작업 순서를 정한다.

## 1-2. 실수했을 때 되돌리기

- **push 전** (아직 내 컴퓨터에만 있음): 직전 커밋 메시지·내용 고치기 `git commit --amend --no-edit` / 커밋 자체 무르기 `git reset HEAD~1` 후 다시 add·commit
- **push 후**: 역사를 고치지 않는다 — 고치는 커밋을 **추가로** 쌓아서 push (force push 금지)
- **리뷰 지적 반영**: 같은 브랜치에 커밋을 추가하고 push하면 **PR이 자동으로 갱신된다** — PR을 새로 만들지 않는다. 갱신 후 리뷰어에게 재확인을 요청한다
- **main에 잘못 머지됨**: 즉시 팀 채팅 공지 → `git revert <머지 커밋>` 브랜치로 되돌림 PR (긴급 시 관리자 바이패스 머지)
- **의견이 갈릴 때**: 명세서가 기준. 명세서에 답이 없으면 그 파트 담당자가 결정하고, 공용 파일 문제면 리드가 결정한다

## 2. 브랜치는 "언제" 만드나 — 작업 단위의 정의

- **브랜치 1개 = 스텁(TODO) 1~2개 구현** 또는 버그 1개 수정. 그 이상 커지면 쪼갠다
- 목표 수명: **만든 당일~2일 안에 머지**. 오래 살수록 main과 벌어져 충돌이 커진다
- 판단 기준: "이 브랜치의 PR을 한 문장으로 설명할 수 있는가?" — 못 하면 너무 크다
- 예: `feat/menu-crud`(등록+조회) → 머지 → `feat/menu-soldout`(품절 토글) → 머지 → `feat/menu-upload`(사진)

## 3. 브랜치 이름 규칙

`{타입}/{파트}-{내용}` — 소문자·하이픈만.

| 파트 접두 | 담당 | 예시 |
| --- | --- | --- |
| order | 홍화수 | `feat/order-create` |
| dashboard | 김재원 | `feat/dashboard-payment` |
| table | 정원준 | `feat/table-session` |
| menu | 권희원 | `feat/menu-crud` |
| settle | 백지연 | `feat/settle-csv` |
| booth | 황대겸 | `feat/booth-login` |

타입: `feat`(새 기능) / `fix`(버그 수정) / `refactor`(동작 동일, 구조 개선) / `docs`(문서) / `chore`(설정·잡일)

## 4. 커밋 메시지 규칙

`타입: 한 일 요약` — 한글 가능, 50자 이내, 타입은 3번과 동일.

```
feat: 메뉴 품절 토글 API 구현
fix: 주문번호 영업일 경계 계산 오류 수정
refactor: 메뉴 조회 로직 서비스 계층으로 이동
```

- 커밋 1개 = 되돌릴 수 있는 한 걸음. "오늘 작업 전부"를 커밋 하나에 몰지 않는다
- AI가 커밋할 경우에도 같은 형식을 따른다

## 5. PR 규칙

1. **크기 상한: 변경 300줄** (생성 파일 포함). 넘으면 브랜치를 쪼개서 PR을 나눈다. 예외: 엔티티+리포지토리+서비스를 처음 만드는 파트 첫 PR은 초과할 수 있다 — PR 본문에 사유를 한 줄 적는다
2. **리뷰어 지정 — 아래 표의 버디에게** (버디 부재 시 다른 팀원 아무나. 단 0번 3항처럼 남의 파일을 고친 PR은 버디 대신 **그 파일 담당자**를 지정):

    | 작성자 | 리뷰어 |
    | --- | --- |
    | 홍화수 ↔ 황대겸 | 서로 |
    | 김재원 ↔ 백지연 | 서로 |
    | 정원준 ↔ 권희원 | 서로 |

3. **PR 본문은 템플릿 4칸을 모두 채운다** (무엇을/왜·명세서 근거/테스트 확인/봐줄 곳). "테스트 확인" 칸에는 **실제로 실행해본 결과**를 적는다 — 안 돌려봤으면 PR을 올리지 않는다
4. **자기 PR 자가 승인 금지** (깃허브가 시스템으로 막는다) — 버디의 승인 1개를 받은 뒤, 머지 버튼은 **작성자 본인이** "Squash and merge"로 누른다
5. 머지 후 브랜치 삭제
6. 리뷰어의 의무: 요청받은 지 **24시간 안에** 리뷰 (승인 또는 코멘트). 코드가 이해 안 되면 "설명해달라"고 요청할 권리가 있고, 작성자는 설명할 의무가 있다

## 6. 공용 파일 (수정 전 팀 채팅 공지 필수)

- `GlobalExceptionHandler.java` — 명세서 §1.4 에러 코드 전부의 핸들러가 이미 있음. 새로 처리할 스프링 예외(500으로 떨어지는 것)를 발견했을 때만 공지 후 핸들러 추가
- `ErrorResponse.java`, `OrderStatus.java`, `PaymentStatus.java`, `*Exception.java` 전부(공용 13개 + 스텁 전용 NotImplementedException) — 공용 계약. 예외는 새로 만들지 말고 있는 것을 골라 쓸 것
- `application.properties`, `build.gradle`, `settings.gradle`, `.gitignore` — 설정·의존성
- `CONTRIBUTING.md`, `README.md`, `.github/` 아래 전부 — 규칙·템플릿·CI
- 공통 레이아웃·공통 CSS(`templates/layout.html`, `static/common.css` 등이 생기면) — 화면 공용

공지 형식: "『파일명』에 ○○ 추가하는 PR 올릴게요" 한 줄이면 충분.

## 6-1. 화면(프론트) 파일 규칙

각자 자기 파트 화면까지 만든다(팀 결정). 충돌 예방을 위해:

- 자기 파트 화면은 자기 폴더에만 만든다: `src/main/resources/templates/{파트}/`, `static/{파트}/` (예: `templates/menu/list.html`)
- 공통 레이아웃·공통 CSS는 6번의 공용 파일 — 수정 전 채팅 공지 필수
- 페이 등 사업자등록이 필요한 기능은 클래스·메서드 자리만 만들고 구현하지 않는다(팀 결정)

## 6-2. CI (자동 빌드 검사)

PR을 올리면 깃허브가 `./gradlew build`를 자동 실행한다(`.github/workflows/build.yml`). 빌드가 깨진 PR은 머지하지 않는다 — push 전에 로컬에서 `./gradlew build` 통과를 확인할 것.

## 7. AI 사용 3책임

AI로 코드·커밋·PR을 만드는 것은 권장된다. 단:

1. **설명 책임** — 리뷰어가 물으면 본인이 설명할 수 있어야 한다. 설명 못 하는 코드는 올리지 않는다
2. **실행 책임** — AI가 짠 코드도 본인이 직접 서버를 켜고 동작 확인 후 PR. "AI가 된다고 했다"는 테스트가 아니다
3. **명세 책임** — 구현 기준은 항상 API 명세서. AI 출력이 명세서와 다르면 명세서가 이긴다

## 8. 처음 시작하는 사람 체크리스트

- [ ] 깃허브 로그인 1회: `gh auth login` (HTTPS 선택 — 브랜치 push와 PR 생성에 반드시 필요)
- [ ] 커밋 신원 설정 1회: `git config --global user.name "본명"` / `git config --global user.email "깃허브 가입 이메일"` — 안 하면 커밋이 내 깃허브 계정과 연결되지 않는다
- [ ] 레포 초대 수락 → `git clone https://github.com/hong0527/boothlock-server.git`
- [ ] `README.md`에서 내 파트·시작 파일 확인
- [ ] `./gradlew bootRun`으로 서버 실행 확인 (내 파트 API 호출 시 501이 나오면 정상)
- [ ] 노션에서 내 파트 API 명세서 상세 확인 (오류 발견 시 수정 후 팀 공유)
- [ ] 위 1번 사이클대로 첫 브랜치를 만들어 스텁 하나를 구현하고 첫 PR 올리기
