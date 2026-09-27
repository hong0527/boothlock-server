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

/** 짧은 "띵-띵" 두 번. 한 번도 풀지 않았으면(사용자 동작 전) 소리 없이 넘어간다.
 * suspended여도 예약은 해 둔다 — 버튼을 누른 직후엔 resume()이 아직 끝나지 않아, 막으면 켤 때 확인음이 안 난다 */
export function playBeep() {
  const ctx = audioContext
  if (!ctx || ctx.state === 'closed') return
  try {
    const start = ctx.currentTime
    for (const offset of [0, 0.25]) {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.value = 880
      // 딸깍 소리 없이 짧게 올렸다 내린다
      gain.gain.setValueAtTime(0.0001, start + offset)
      gain.gain.exponentialRampToValueAtTime(0.4, start + offset + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + offset + 0.18)
      osc.connect(gain).connect(ctx.destination)
      osc.start(start + offset)
      osc.stop(start + offset + 0.2)
    }
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
