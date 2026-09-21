/**
 * localStorage 안전 래퍼 — 손님 폰의 브라우저 설정을 우리가 통제할 수 없어서 필요하다.
 *
 * <p>실측(iOS 18 Safari, 운영 환경): 세션 발급(C1)은 200으로 성공하는데 저장한 sessionToken을
 * 곧바로 읽으면 null이라, 손님이 "세션이 만료됐어요" 화면에 갇혀 재스캔을 반복해도 영원히
 * 주문에 못 들어가는 사고가 있었다. 서버에는 세션이 실제로 만들어져 좌석만 사용 중으로 잡혔다.
 *
 * <p>막히는 방식이 두 가지라 둘 다 막아야 한다:
 * <ul>
 *   <li>던지는 경우 — 쿠키·사이트 데이터 차단, 저장공간 부족이면 접근 자체가 SecurityError/QuotaExceededError</li>
 *   <li><b>조용히 무시하는 경우</b> — setItem이 예외 없이 통과하는데 getItem은 비어 있다.
 *       try/catch만으로는 못 잡아서, 쓰고 나서 되읽어 확인한다</li>
 * </ul>
 *
 * <p>어느 쪽이든 이 탭의 메모리에 들고 가 주문 흐름은 끝까지 되게 한다. 대신 새로고침하면 날아가는데,
 * 그때는 QR을 다시 찍으면 서버가 같은 활성 세션을 그대로 돌려주므로(C1 복원) 주문 내역까지 살아난다.
 */

/** 저장소가 막혔을 때의 폴백 — 탭이 살아 있는 동안만 유지된다 */
const memory = new Map<string, string>()

export function readStored(key: string): string | null {
  try {
    const value = localStorage.getItem(key)
    if (value !== null) return value
  } catch {
    // 접근 자체가 막힌 환경 — 아래 메모리 폴백으로 내려간다
  }
  return memory.get(key) ?? null
}

export function writeStored(key: string, value: string) {
  // 메모리를 먼저 채운다 — localStorage가 어떻게 실패하든 이 탭에서는 읽히게
  memory.set(key, value)
  try {
    localStorage.setItem(key, value)
  } catch {
    // 무시 — 메모리에 이미 있다
  }
}

export function removeStored(key: string) {
  memory.delete(key)
  try {
    localStorage.removeItem(key)
  } catch {
    // 무시 — 메모리에서는 지웠다
  }
}

/** JSON 값 읽기. 깨진 값이 남아 있어도 화면이 죽지 않게 null로 떨어뜨린다(렌더 중에 호출되는 자리가 있다) */
export function readStoredJson<T>(key: string): T | null {
  const raw = readStored(key)
  if (raw === null) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    // 한 번 깨지면 새로고침해도 계속 깨지므로 지워서 다음 진입을 살린다
    removeStored(key)
    return null
  }
}

/**
 * 이 브라우저가 실제로 저장을 유지하는지 — 쓰고 되읽어 확인한다.
 * "세션 만료"(서버가 세션을 끝냄)와 "브라우저가 저장을 막음"은 손님이 할 일이 달라서 문구를 갈라야 한다.
 */
export function isStorageUsable(): boolean {
  const probeKey = '__boothlock_storage_probe__'
  try {
    localStorage.setItem(probeKey, '1')
    const ok = localStorage.getItem(probeKey) === '1'
    localStorage.removeItem(probeKey)
    return ok
  } catch {
    return false
  }
}
