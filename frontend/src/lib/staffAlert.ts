import { readStored, writeStored } from './safeStorage'

/**
 * 운영자 새 주문·호출 알림의 기기 쪽 동작 — 소리(WebAudio 비프)·진동·화면 꺼짐 방지(wakeLock).
 *
 * <p>소리 파일을 두지 않고 OscillatorNode로 직접 만든다 — 에셋 로딩 실패(축제장 와이파이) 걱정이 없다.
 *
 * <p>iOS Safari는 사용자 동작(탭) 안에서 한 번 resume()한 AudioContext만 소리를 낸다. 그래서 "알림 켜기" 버튼이
 * {@link unlockAudio}를 부르고, 새로고침 뒤 설정만 남아 있을 때는 화면 아무 곳이나 누르는 순간 풀리게 한다({@link listenForAudioUnlock}).
 * 모든 API는 없는 브라우저(구형 기기·테스트 환경)에서 조용히 넘어간다 — 알림 때문에 주문현황이 죽으면 안 된다.
 */

const PREF_KEY = 'boothlock_staff_alert'

type AudioContextCtor = typeof AudioContext

let audioContext: AudioContext | null = null
let wakeLock: WakeLockSentinel | null = null
// request()가 끝나기 전에 알림을 끄거나(release) 두 번 잡으면(acquire 겹침) 잠금이 새어 화면이 계속 켜져 있다.
// wanted = 지금 잡고 싶은가(마지막 호출 기준), inFlight = 요청이 이미 나가 있는가
let wakeLockWanted = false
let wakeLockInFlight = false

export function isAlertPreferred(): boolean {
  return readStored(PREF_KEY) === 'on'
}

export function setAlertPreferred(on: boolean) {
  writeStored(PREF_KEY, on ? 'on' : 'off')
}

/** 사용자 동작(클릭·터치) 안에서 불러야 iOS에서 소리가 풀린다 */
export function unlockAudio() {
  if (typeof window === 'undefined') return
  try {
    if (!audioContext) {
      const Ctor: AudioContextCtor | undefined =
        window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioContextCtor }).webkitAudioContext
      if (!Ctor) return
      audioContext = new Ctor()
    }
    resumeAudio()
  } catch {
    // 오디오를 못 쓰는 환경 — 진동·탭 제목 알림은 그대로 동작한다
  }
}

/** 이미 만든 오디오를 다시 깨운다 — 새로 만들지는 않는다(사용자 동작 밖에서 만들면 iOS에서 어차피 잠겨 있다).
 * iOS는 전화·다른 앱 소리로 끊기면 'interrupted'가 되고 스스로 돌아오지 않는다 — 탭에 돌아올 때 이것을 부른다 */
export function resumeAudio() {
  const ctx = audioContext
  if (!ctx || ctx.state === 'running' || ctx.state === 'closed') return
  try {
    void ctx.resume().catch(() => {})
  } catch {
    // 무시
  }
}

/** 알림을 끄면 오디오도 재운다 — 켜 둔 채면 iOS가 백그라운드에서도 오디오 세션을 붙잡는다 */
export function suspendAudio() {
  const ctx = audioContext
  if (!ctx || ctx.state !== 'running') return
  try {
    void ctx.suspend().catch(() => {})
  } catch {
    // 무시
  }
}

function isAudioRunning() {
  return audioContext?.state === 'running'
}

/**
 * 새로고침 뒤처럼 설정만 켜져 있고 오디오가 잠긴 경우 — 화면을 누를 때마다 풀기를 시도하고, 실제로 풀린 뒤에만 그만 듣는다.
 * pointerdown 한 번(once)만 들으면 iOS 구버전은 그 이벤트를 사용자 동작으로 안 쳐서 영영 안 풀린다 — click·touchend도 같이 듣는다.
 * resume()은 비동기라 누른 순간엔 아직 running이 아닐 수 있어, 끝난 뒤에도 한 번 더 확인한다. 반환값은 해제 함수
 */
export function listenForAudioUnlock(): () => void {
  if (typeof document === 'undefined') return () => {}
  const events = ['pointerdown', 'click', 'touchend'] as const
  const stop = () => events.forEach((type) => document.removeEventListener(type, onGesture))
  function onGesture() {
    unlockAudio()
    if (isAudioRunning()) {
      stop()
      return
    }
    void audioContext?.resume().then(() => { if (isAudioRunning()) stop() }).catch(() => {})
  }
  if (isAudioRunning()) return () => {}
  events.forEach((type) => document.addEventListener(type, onGesture))
  return stop
}

// 종소리 배음비(기본음 대비) — 완전한 정수배가 아니라 실제 종처럼 약간 어긋난 배음이 "쨍한 비프음"이 아니라
// "종이 울리는" 느낌을 낸다. 두 번째·세 번째 배음은 더 작게 섞는다
const BELL_PARTIALS = [
  { ratio: 1, gain: 1 },
  { ratio: 2.4, gain: 0.35 },
  { ratio: 3.8, gain: 0.18 },
]

/** 종 하나를 울린다 — 배음을 섞은 사인파에 지수 감쇠(빠른 어택·느린 감쇠)를 입혀 "땡" 소리를 만든다 */
function ringBell(ctx: AudioContext, at: number, freq: number, peakGain: number, decaySeconds: number) {
  for (const partial of BELL_PARTIALS) {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = freq * partial.ratio
    const partialPeak = peakGain * partial.gain
    // 0에서 바로 올리면(setValueAtTime) 딸깍 소리가 나서, 아주 작은 값에서 지수적으로 올린다
    gain.gain.setValueAtTime(0.0001, at)
    gain.gain.exponentialRampToValueAtTime(partialPeak, at + 0.01)
    gain.gain.exponentialRampToValueAtTime(0.0001, at + decaySeconds)
    osc.connect(gain).connect(ctx.destination)
    osc.start(at)
    osc.stop(at + decaySeconds + 0.05)
  }
}

/**
 * 새 주문 알림음 — 같은 음(880Hz)이 두 번 울리는 "땡-땡". 한 번도 풀지 않았으면(사용자 동작 전) 소리 없이 넘어간다.
 *
 * audioContext가 아직 없으면(알림을 켠 뒤 한 번도 화면을 만지기 전에 첫 주문이 온 경우 등) 여기서도
 * unlockAudio를 한 번 시도한다 — 사용자 동작 밖이라 대부분 막히지만, 이미 다른 경로로 한 번이라도
 * 풀린 적이 있다면(예: 다른 오디오 재생) 그 컨텍스트를 그대로 쓸 수 있어 손해 볼 게 없다.
 * suspended여도 예약은 해 둔다 — 버튼을 누른 직후엔 resume()이 아직 끝나지 않아, 막으면 켤 때 확인음이 안 난다.
 * 축제장 소음 속에서도 들리도록 기존(짧은 사인파 비프)보다 크고 길게, 배음을 섞어 종소리에 가깝게 만든다
 */
export function playOrderAlert() {
  if (!audioContext) unlockAudio()
  const ctx = audioContext
  if (!ctx || ctx.state === 'closed') return
  try {
    const start = ctx.currentTime
    ringBell(ctx, start, 880, 0.7, 0.55)
    ringBell(ctx, start + 0.35, 880, 0.7, 0.55)
  } catch {
    // 무시 — 소리 한 번 못 낸 것으로 화면이 멈추면 안 된다
  }
}

/**
 * 직원 호출 알림음 — 손님이 화면에서 직접 부른 것이라 {@link playOrderAlert}(새 주문)와 소음 속에서도
 * 헷갈리지 않게, 같은 음 두 번이 아니라 완전5도(E5·B5) 두 음을 빠르게 네 번 번갈아 울리는 "초인종" 패턴을 쓴다.
 * 나머지 동작(오디오 잠금·오류 무시)은 새 주문 알림과 같다
 */
export function playCallAlert() {
  if (!audioContext) unlockAudio()
  const ctx = audioContext
  if (!ctx || ctx.state === 'closed') return
  try {
    const start = ctx.currentTime
    const LOW = 659.25 // E5
    const HIGH = 987.77 // B5
    ringBell(ctx, start, LOW, 0.7, 0.28)
    ringBell(ctx, start + 0.2, HIGH, 0.7, 0.28)
    ringBell(ctx, start + 0.4, LOW, 0.7, 0.28)
    ringBell(ctx, start + 0.6, HIGH, 0.7, 0.4)
  } catch {
    // 무시 — 소리 한 번 못 낸 것으로 화면이 멈추면 안 된다
  }
}

export function vibrate() {
  try {
    navigator.vibrate?.(200)
  } catch {
    // 무시 — iOS는 vibrate 자체가 없다
  }
}

/** 화면 꺼짐 방지 — 탭이 가려지면 브라우저가 자동으로 풀어 버리므로, 돌아올 때마다 다시 부른다 */
export async function acquireWakeLock() {
  wakeLockWanted = true
  if (typeof document === 'undefined' || document.visibilityState !== 'visible') return
  // 타입상 항상 있지만 구형 iOS·HTTP 접속에서는 실제로 없다
  const api: WakeLock | undefined = navigator.wakeLock
  // 이미 요청 중이면 그 결과를 쓴다 — 겹쳐 보내면 먼저 온 잠금이 덮여 풀 방법이 없어진다
  if (!api || wakeLock || wakeLockInFlight) return
  wakeLockInFlight = true
  try {
    const lock = await api.request('screen')
    // 기다리는 사이 알림을 껐다(화면을 떠났다) — 받자마자 돌려준다
    if (!wakeLockWanted) {
      void lock.release().catch(() => {})
      return
    }
    wakeLock = lock
    // 브라우저가 풀면(탭 전환·저전력) 다음 visibilitychange에서 다시 잡도록 비워 둔다
    lock.addEventListener('release', () => {
      if (wakeLock === lock) wakeLock = null
    })
  } catch {
    // 저전력 모드 등으로 거부될 수 있다 — 알림 자체는 계속 동작한다
  } finally {
    wakeLockInFlight = false
  }
}

export function releaseWakeLock() {
  wakeLockWanted = false
  const lock = wakeLock
  wakeLock = null
  void lock?.release().catch(() => {})
}
