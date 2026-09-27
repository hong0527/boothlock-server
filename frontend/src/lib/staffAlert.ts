import { readStored, writeStored } from './safeStorage'

/**
 * 운영자 새 주문·호출 알림의 기기 쪽 동작 — 소리(WebAudio 비프)·진동·화면 꺼짐 방지(wakeLock).
 *
 * <p>소리 파일을 두지 않고 OscillatorNode로 직접 만든다 — 에셋 로딩 실패(축제장 와이파이) 걱정이 없다.
 *
 * <p>iOS Safari는 사용자 동작(탭) 안에서 한 번 resume()한 AudioContext만 소리를 낸다. 그래서 "알림 켜기" 버튼이
 * {@link unlockAudio}를 부르고, 새로고침 뒤 설정만 남아 있을 때는 화면 아무 곳이나 처음 누르는 순간 풀리게 한다.
 * 모든 API는 없는 브라우저(구형 기기·테스트 환경)에서 조용히 넘어간다 — 알림 때문에 주문현황이 죽으면 안 된다.
 */

const PREF_KEY = 'boothlock_staff_alert'

type AudioContextCtor = typeof AudioContext

let audioContext: AudioContext | null = null
let wakeLock: WakeLockSentinel | null = null

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
    if (audioContext.state === 'suspended') void audioContext.resume().catch(() => {})
  } catch {
    // 오디오를 못 쓰는 환경 — 진동·탭 제목 알림은 그대로 동작한다
  }
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
  if (typeof document === 'undefined' || document.visibilityState !== 'visible') return
  // 타입상 항상 있지만 구형 iOS·HTTP 접속에서는 실제로 없다
  const api: WakeLock | undefined = navigator.wakeLock
  if (!api || wakeLock) return
  try {
    const lock = await api.request('screen')
    wakeLock = lock
    // 브라우저가 풀면(탭 전환·저전력) 다음 visibilitychange에서 다시 잡도록 비워 둔다
    lock.addEventListener('release', () => {
      if (wakeLock === lock) wakeLock = null
    })
  } catch {
    // 저전력 모드 등으로 거부될 수 있다 — 알림 자체는 계속 동작한다
  }
}

export function releaseWakeLock() {
  const lock = wakeLock
  wakeLock = null
  void lock?.release().catch(() => {})
}
