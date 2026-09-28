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

/*
 * 알림음 두 가지 — 소리만 듣고 무엇인지 알 수 있게 음색·리듬·음높이를 모두 다르게 한다.
 * - 새 주문: 배음을 섞은 종소리로 같은 음을 두 번 "땡-땡"(약 0.9초). 여운이 남아 소음 속에서도 놓치지 않는다.
 * - 직원호출: 여운 없는 짧은 전자음으로 두 음을 번갈아 "딩동 · 딩동"(약 0.7초).
 *
 * 폴링에서 부르는 알림음은 오디오가 실제로 돌고 있을(running) 때만 예약한다. 잠긴(suspended·interrupted) 동안에는
 * currentTime이 멈춰 있어 예약한 소리가 그대로 쌓였다가, 나중에 화면을 누르는 순간 한꺼번에 겹쳐 터진다.
 * 두 소리가 한 번에 오면 먼저 예약된 소리가 끝난 뒤에 이어서 낸다(nextFreeAt, 오디오 시계 기준 — setTimeout을 쓰지 않는다).
 */

// 앞서 예약한 알림음이 끝나는 시각(AudioContext 시간) — 뒤 소리를 여기부터 낸다
let nextFreeAt = 0
// 두 소리가 붙어 한 소리로 들리지 않게 띄우는 간격(초)
const PATTERN_GAP = 0.15
// 예약해 둔 소리 — 알림을 끄면 stopAlertSounds가 한꺼번에 끊는다(화면을 떠나는 것만으로는 끊지 않는다)
const scheduledOscillators = new Set<OscillatorNode>()
// 직원호출 음색용 저역통과 필터 — 컨텍스트가 하나뿐이라 한 번 만들어 재사용한다
let callFilter: BiquadFilterNode | null = null
// stopAlertSounds를 부를 때마다 올린다 — resume()을 기다리던 확인음이 그 사이 알림을 끈 뒤에 울리지 않게
let stopGeneration = 0

/** 음 하나를 예약한다 — 0에서 바로 올리면 딸깍 소리가 나서, 아주 작은 값에서 지수적으로 올렸다 내린다 */
function scheduleTone(
  ctx: AudioContext, destination: AudioNode, type: OscillatorType,
  freq: number, at: number, peak: number, attack: number, decaySeconds: number,
) {
  const osc = ctx.createOscillator()
  const gain = ctx.createGain()
  osc.type = type
  osc.frequency.value = freq
  gain.gain.setValueAtTime(0.0001, at)
  gain.gain.exponentialRampToValueAtTime(peak, at + attack)
  gain.gain.exponentialRampToValueAtTime(0.0001, at + decaySeconds)
  osc.connect(gain).connect(destination)
  scheduledOscillators.add(osc)
  osc.onended = () => {
    scheduledOscillators.delete(osc)
    gain.disconnect()
  }
  osc.start(at)
  osc.stop(at + decaySeconds + 0.05)
}

/**
 * 폴링용 공통 진입점 — 오디오가 running일 때만 예약하고, 앞 소리가 끝난 뒤에 이어 붙인다. build는 소리 길이(초)를 돌려준다.
 *
 * audioContext가 아직 없으면(새로고침 뒤 화면을 한 번도 만지기 전 등) 여기서도 unlockAudio를 한 번 시도한다 —
 * 사용자 동작 밖이라 대부분 suspended로 만들어져 이번 소리는 건너뛰지만, 페이지에 이미 사용자 동작이 있었으면
 * (Chrome·Android에서 다른 화면을 누르고 들어온 경우) 바로 running이라 그대로 울린다.
 */
function playPattern(build: (ctx: AudioContext, start: number) => number) {
  if (!audioContext) unlockAudio()
  const ctx = audioContext
  if (!ctx || ctx.state !== 'running') return
  try {
    const start = Math.max(ctx.currentTime, nextFreeAt)
    nextFreeAt = start + build(ctx, start) + PATTERN_GAP
  } catch {
    // 무시 — 소리 한 번 못 낸 것으로 화면이 멈추면 안 된다
  }
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
    scheduleTone(ctx, ctx.destination, 'sine', freq * partial.ratio, at, peakGain * partial.gain, 0.01, decaySeconds)
  }
}

const ORDER_FREQ = 880
// 배음까지 더한 최고 음량이 0.45 × (1 + 0.35 + 0.18) ≈ 0.69 — 1을 넘으면 기기에 따라 소리가 찢어진다
const ORDER_PEAK = 0.45
const ORDER_BELL_GAP = 0.35
const ORDER_BELL_DECAY = 0.55

/** 새 주문 알림음 — 같은 음(880Hz)이 두 번 울리는 "땡-땡". 오디오가 잠겨 있으면 소리 없이 넘어간다(진동·탭 제목은 그대로) */
export function playOrderAlert() {
  playPattern((ctx, start) => {
    ringBell(ctx, start, ORDER_FREQ, ORDER_PEAK, ORDER_BELL_DECAY)
    ringBell(ctx, start + ORDER_BELL_GAP, ORDER_FREQ, ORDER_PEAK, ORDER_BELL_DECAY)
    return ORDER_BELL_GAP + ORDER_BELL_DECAY
  })
}

const CALL_HIGH = 987.77 // B5
const CALL_LOW = 659.25 // E5
// 사각파는 같은 음량에서도 사인파보다 훨씬 크게 들린다 — 최고 음량을 낮게 잡는다
const CALL_PEAK = 0.16
const CALL_PULSE = 0.12
// "딩동 · 딩동" — [시작(초), 음높이]
const CALL_NOTES: [number, number][] = [[0, CALL_HIGH], [0.15, CALL_LOW], [0.42, CALL_HIGH], [0.57, CALL_LOW]]

/**
 * 직원 호출 알림음 — 손님이 직접 부른 것이라 {@link playOrderAlert}(새 주문)와 소음 속에서도 헷갈리지 않게,
 * 종소리가 아니라 여운 없는 짧은 전자음(사각파)으로 두 음을 번갈아 "딩동 · 딩동" 울린다.
 * 사각파의 거친 윗배음은 저역통과로 걷어 내 또렷하지만 귀를 찌르지 않게 한다
 */
export function playCallAlert() {
  playPattern((ctx, start) => {
    if (!callFilter) {
      callFilter = ctx.createBiquadFilter()
      callFilter.type = 'lowpass'
      callFilter.frequency.value = 2500
      callFilter.connect(ctx.destination)
    }
    for (const [offset, freq] of CALL_NOTES) {
      scheduleTone(ctx, callFilter, 'square', freq, start + offset, CALL_PEAK, 0.005, CALL_PULSE)
    }
    return CALL_NOTES[CALL_NOTES.length - 1][0] + CALL_PULSE
  })
}

/**
 * 알림을 켤 때 확인음 — 소리 크기를 확인하게 주문 알림음을 한 번 낸다. 버튼을 누른 직후엔 resume()이 아직
 * 끝나지 않아 suspended라, 폴링용 규칙(running만)으로는 안 울린다 — 풀린 뒤에 낸다
 */
export function previewOrderAlert() {
  const ctx = audioContext
  if (!ctx || ctx.state === 'closed') return
  if (ctx.state === 'running') {
    playOrderAlert()
    return
  }
  const generation = stopGeneration
  try {
    void ctx.resume().then(() => { if (generation === stopGeneration) playOrderAlert() }).catch(() => {})
  } catch {
    // 무시
  }
}

/**
 * 예약해 둔 알림음을 모두 끊는다 — 알림을 끌 때 부른다. 끈 뒤에 이어질 소리가 울리거나, 멈춘(suspend) 오디오에
 * 남아 있다가 다음에 켤 때 한꺼번에 터지지 않게. 화면 이동에는 부르지 않는다 — 이미 들어온 알림의 소리는 끝까지 울린다
 */
export function stopAlertSounds() {
  for (const osc of scheduledOscillators) {
    try {
      osc.disconnect()
      osc.stop()
    } catch {
      // 이미 끝난 소리 — 무시
    }
  }
  scheduledOscillators.clear()
  nextFreeAt = 0
  stopGeneration += 1
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
