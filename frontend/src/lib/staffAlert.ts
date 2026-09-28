import { readStored, writeStored } from './safeStorage'
import orderAlertClipUrl from '../assets/sounds/order-alert.mp3'
import callAlertClipUrl from '../assets/sounds/call-alert.mp3'

/**
 * 운영자 새 주문·호출 알림의 기기 쪽 동작 — 소리(WebAudio 비프)·진동·화면 꺼짐 방지(wakeLock).
 *
 * <p>새 주문·직원호출 알림음 둘 다 번들에 같이 실려 배포되는 짧은 음원(2026-09-29 파일럿 피드백, 팀이 고른 클립)을
 * 쓴다. 못 받거나 디코딩에 실패하면(축제장 와이파이 등) 각자 원래 있던 합성음으로 조용히 대신한다
 * ({@link createClipAlert}) — 음원 파일이 어떻게 되든 알림 자체는 항상 소리가 난다.
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

// 알림 켜고 끔은 이 저장값 하나만 쓴다 — 설정 화면과 주문현황의 빠른 전환이 같은 값을 보고, 바뀌면 서로 알린다
const prefListeners = new Set<(on: boolean) => void>()

export function isAlertPreferred(): boolean {
  return readStored(PREF_KEY) === 'on'
}

export function setAlertPreferred(on: boolean) {
  writeStored(PREF_KEY, on ? 'on' : 'off')
  prefListeners.forEach((listener) => listener(on))
}

/**
 * 알림 켜고 끔이 바뀌면 알려 준다 — 같은 탭의 다른 화면(설정 ↔ 주문현황)은 setAlertPreferred가, 같은 브라우저의
 * 다른 탭은 storage 이벤트가 알린다. 설정은 기기(브라우저)마다 따로다 — 다른 기기의 설정은 바꾸지 않는다. 반환값은 해제 함수
 */
export function subscribeAlertPreference(listener: (on: boolean) => void): () => void {
  prefListeners.add(listener)
  const onStorage = (event: StorageEvent) => {
    if (event.key === PREF_KEY) listener(event.newValue === 'on')
  }
  const target = typeof window === 'undefined' ? null : window
  target?.addEventListener?.('storage', onStorage)
  return () => {
    prefListeners.delete(listener)
    target?.removeEventListener?.('storage', onStorage)
  }
}

/** 알림 켜기 — 사용자 동작(클릭) 안에서 불러야 iOS에서 소리가 풀린다. 켜는 순간 확인음을 한 번 내 소리 크기도 확인하게 한다 */
export function turnAlertsOn() {
  unlockAudio()
  previewOrderAlert()
  setAlertPreferred(true)
}

/** 알림 끄기 — 예약된 소리를 끊고 오디오를 재운다(켜 둔 채면 iOS가 백그라운드에서도 오디오 세션을 붙잡는다) */
export function turnAlertsOff() {
  stopAlertSounds()
  suspendAudio()
  setAlertPreferred(false)
}

const VOLUME_KEY = 'boothlock_staff_alert_volume'
/** 알림음 크기 기본값(%) — 이 값에서 예전(볼륨 설정이 없던 때)과 같은 크기로 들린다 */
export const DEFAULT_ALERT_VOLUME = 70

/** 알림음 크기(0~100%) — 이 웹앱이 내는 알림음에만 곱한다. 기기·브라우저 자체 볼륨은 건드리지 않는다 */
export function getAlertVolume(): number {
  const raw = readStored(VOLUME_KEY)
  const value = raw === null ? NaN : Number(raw)
  return Number.isFinite(value) ? clampVolume(value) : DEFAULT_ALERT_VOLUME
}

/** 알림을 꺼도 값은 남는다. 바꾸면 다음 알림음부터(이미 울리는 소리도 부드럽게) 바로 반영된다 */
export function setAlertVolume(volume: number) {
  const value = clampVolume(volume)
  writeStored(VOLUME_KEY, String(value))
  const ctx = audioContext
  if (!ctx || !masterGain) return
  try {
    masterGain.gain.setTargetAtTime(volumeToGain(value), ctx.currentTime, 0.02)
  } catch {
    // 무시
  }
}

function clampVolume(volume: number): number {
  return Math.min(100, Math.max(0, Math.round(volume)))
}

/**
 * % → 곱할 크기. 귀는 크기를 로그로 느껴서 직선으로 두면 아래쪽 절반이 거의 무음처럼 들린다 — 1.5제곱으로 완만하게 한다.
 * 기본값(70%)이 1(예전 크기)이 되게 맞춘다. 100%는 약 1.7배라 한계를 넘는 부분은 아래 리미터가 눌러 찢어지지 않게 한다
 */
function volumeToGain(volume: number): number {
  return Math.pow(volume / DEFAULT_ALERT_VOLUME, 1.5)
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
    orderAlert.ensureLoaded(audioContext)
    callAlert.ensureLoaded(audioContext)
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
 * 알림음 두 가지 — 소리만 듣고 무엇인지 알 수 있게 서로 다른 실제 음원을 쓴다(팀이 고른 클립, 2026-09-29).
 * - 새 주문: order-alert.mp3(약 1초) — 준비 전이거나 못 받았으면 맑은 벨 음색으로 네 음이 이어지며 점점
 *   높아지는 합성음 "띠디리링↗"(C6→E6→G6→C7, 마지막 음을 길게, 약 1.8초)으로 대신한다.
 * - 직원호출: call-alert.mp3(약 1초) — 마찬가지로 준비 전이거나 못 받았으면 여운 없는 짧은 전자음으로
 *   내려가는 두 음 "딩동 · 딩동"(약 0.7초)으로 대신한다.
 * 서로 다른 클립이라 소음 속에서도 새 주문과 호출이 갈린다. 둘 다 알림음 크기(마스터 게인)를 함께 탄다.
 *
 * 폴링에서 부르는 알림음은 오디오가 실제로 돌고 있을(running) 때만 예약한다. 잠긴(suspended·interrupted) 동안에는
 * currentTime이 멈춰 있어 예약한 소리가 그대로 쌓였다가, 나중에 화면을 누르는 순간 한꺼번에 겹쳐 터진다.
 * 두 소리가 한 번에 오면 먼저 예약된 소리가 끝난 뒤에 이어서 낸다(nextFreeAt, 오디오 시계 기준 — setTimeout을 쓰지 않는다).
 */

// 앞서 예약한 알림음이 끝나는 시각(AudioContext 시간) — 뒤 소리를 여기부터 낸다
let nextFreeAt = 0
// 두 소리가 붙어 한 소리로 들리지 않게 띄우는 간격(초)
const PATTERN_GAP = 0.15
// 예약해 둔 소리(오실레이터·실제 음원 재생 둘 다) — 알림을 끄면 stopAlertSounds가 한꺼번에 끊는다
// (화면을 떠나는 것만으로는 끊지 않는다). OscillatorNode·AudioBufferSourceNode 둘 다 AudioScheduledSourceNode다
const scheduledSources = new Set<AudioScheduledSourceNode>()
// 직원호출 음색용 저역통과 필터 — 컨텍스트가 하나뿐이라 한 번 만들어 재사용한다
let callFilter: BiquadFilterNode | null = null
// 모든 알림음이 지나는 출구 — 알림음 크기(마스터 게인) → 리미터 → 스피커. 컨텍스트가 하나뿐이라 한 번 만든다
let masterGain: GainNode | null = null

/**
 * 알림음 출구를 돌려준다(처음이면 만든다). 소리마다 지금 저장된 크기로 맞춘다 — 다른 탭(설정)에서 바꾼 크기도 따라간다.
 * 리미터(DynamicsCompressor)는 큰 볼륨에서 여러 배음이 겹쳐 1을 넘을 때 찢어지는 대신 눌러 준다
 */
function alertOutput(ctx: AudioContext): AudioNode {
  if (!masterGain) {
    const gain = ctx.createGain()
    const limiter = ctx.createDynamicsCompressor()
    limiter.threshold.value = -3
    limiter.knee.value = 3
    limiter.ratio.value = 20
    limiter.attack.value = 0.002
    limiter.release.value = 0.1
    gain.connect(limiter).connect(ctx.destination)
    masterGain = gain
  }
  masterGain.gain.setValueAtTime(volumeToGain(getAlertVolume()), ctx.currentTime)
  return masterGain
}
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
  scheduledSources.add(osc)
  osc.onended = () => {
    scheduledSources.delete(osc)
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

// 주문음 음색 — 정수배 배음(2·3배)을 옅게 섞은 사인파(맑은 벨). 위쪽 배음은 기음보다 빨리 사라지게 해(decay 비율)
// 마지막 음을 길게 끌어도 쨍하게 남지 않고 둥근 여운만 남는다
const CHIME_PARTIALS = [
  { ratio: 1, gain: 1, decay: 1 },
  { ratio: 2, gain: 0.3, decay: 0.6 },
  { ratio: 3, gain: 0.12, decay: 0.4 },
]

/** 맑은 벨 음 하나 — 5ms 만에 올라 decaySeconds에 걸쳐 사라진다 */
function ringChime(ctx: AudioContext, at: number, freq: number, peakGain: number, decaySeconds: number) {
  const output = alertOutput(ctx)
  for (const partial of CHIME_PARTIALS) {
    scheduleTone(ctx, output, 'sine', freq * partial.ratio, at, peakGain * partial.gain, 0.005, decaySeconds * partial.decay)
  }
}

// "띠 → 디 → 리 → 링↗" — 장3화음을 한 옥타브 타고 오르는 하나의 벨 소리. [시작(초), 음높이, 울림(초), 최고 음량].
// 음 사이 0.1초에 앞 음이 0.45초 울려 뒤 음과 겹치며 이어진다(끊긴 "띠링 · 띠링"이 아니라 한 줄기). 마지막 "링"은 1.5초 끌어
// 끝맺는다 — 전체 약 1.8초. 겹친 꼬리까지 더해도 최고 약 0.6이라 찢어지지 않고, 마지막 음(0.49 × 배음 합 1.42 ≈ 0.7)이
// 기본 크기(70%)에서 예전 주문음과 비슷한 크기로 가장 또렷하다
const ORDER_NOTES: [number, number, number, number][] = [
  [0, 1046.5, 0.45, 0.42], // C6 띠
  [0.1, 1318.51, 0.45, 0.42], // E6 디
  [0.2, 1567.98, 0.45, 0.42], // G6 리
  [0.3, 2093, 1.5, 0.49], // C7 링 — 길게
]

const CALL_HIGH = 987.77 // B5
const CALL_LOW = 659.25 // E5
// 사각파는 같은 음량에서도 사인파보다 훨씬 크게 들린다 — 최고 음량을 낮게 잡는다
const CALL_PEAK = 0.16
const CALL_PULSE = 0.12
// "딩동 · 딩동" — [시작(초), 음높이]
const CALL_NOTES: [number, number][] = [[0, CALL_HIGH], [0.15, CALL_LOW], [0.42, CALL_HIGH], [0.57, CALL_LOW]]

/** 직원호출 합성음 — 종소리가 아니라 여운 없는 짧은 전자음(사각파)으로 두 음을 번갈아 "딩동 · 딩동" 울린다.
 * 사각파의 거친 윗배음은 저역통과로 걷어 내 또렷하지만 귀를 찌르지 않게 한다 */
function synthCallAlert(ctx: AudioContext, start: number): number {
  if (!callFilter) {
    callFilter = ctx.createBiquadFilter()
    callFilter.type = 'lowpass'
    callFilter.frequency.value = 2500
    callFilter.connect(alertOutput(ctx))
  } else {
    alertOutput(ctx) // 지금 저장된 알림음 크기로 맞춘다
  }
  for (const [offset, freq] of CALL_NOTES) {
    scheduleTone(ctx, callFilter, 'square', freq, start + offset, CALL_PEAK, 0.005, CALL_PULSE)
  }
  return CALL_NOTES[CALL_NOTES.length - 1][0] + CALL_PULSE
}

/** 새 주문 합성음 — 장3화음을 한 옥타브 타고 오르는 벨 소리 "띠 → 디 → 리 → 링↗". 음 사이 0.1초에 앞 음이
 * 0.45초 울려 뒤 음과 겹치며 이어진다(끊긴 "띠링 · 띠링"이 아니라 한 줄기). 마지막 "링"은 1.5초 끌어 끝맺는다 */
function synthOrderAlert(ctx: AudioContext, start: number): number {
  let end = 0
  for (const [offset, freq, decay, peak] of ORDER_NOTES) {
    ringChime(ctx, start + offset, freq, peak, decay)
    end = Math.max(end, offset + decay)
  }
  return end
}

/** 실제 음원 재생을 예약한다 — 알림음 크기(alertOutput)를 그대로 타서 볼륨 설정이 똑같이 적용된다 */
function scheduleClip(ctx: AudioContext, buffer: AudioBuffer, at: number): number {
  const src = ctx.createBufferSource()
  src.buffer = buffer
  src.connect(alertOutput(ctx))
  scheduledSources.add(src)
  src.onended = () => scheduledSources.delete(src)
  src.start(at)
  return buffer.duration
}

/**
 * 실제 음원 + 합성음 폴백을 묶은 알림 하나 — 새 주문·직원호출이 같은 모양이라(2026-09-29 파일럿 피드백으로
 * 직원호출도 실제 음원을 쓰게 되면서) 공통으로 뺐다. 음원을 못 받거나 디코딩에 실패해도(축제장 와이파이 등)
 * synth로 조용히 대신한다 — 알림음 하나 때문에 주문현황이 죽거나 무음이 되면 안 된다는 원칙을 그대로 따른다.
 */
function createClipAlert(url: string, synth: (ctx: AudioContext, start: number) => number) {
  let buffer: AudioBuffer | null = null
  let loading = false

  /** 음원을 미리 받아 디코딩해 둔다 — 알림을 켤 때(unlockAudio) 한 번, 실패하면 다음 알림음을 낼 때 다시 시도한다 */
  function ensureLoaded(ctx: AudioContext) {
    if (buffer || loading) return
    loading = true
    fetch(url)
      .then((res) => res.arrayBuffer())
      .then((data) => ctx.decodeAudioData(data))
      .then((decoded) => { buffer = decoded })
      .catch(() => {
        // 무시 — 다음 play() 호출이 다시 시도하고, 그때까지는 합성음이 대신 울린다
      })
      .finally(() => { loading = false })
  }

  /** 음원이 준비돼 있으면 그걸, 아직이면(첫 알림 등) 합성음으로 울리면서 다음 알림을 위해 음원을 마저 받아 둔다.
   * 오디오가 잠겨 있으면 소리 없이 넘어간다(진동·탭 제목은 그대로) */
  function play() {
    if (!audioContext) unlockAudio()
    const ctx = audioContext
    if (ctx && !buffer) ensureLoaded(ctx)
    const loaded = buffer
    if (loaded) {
      playPattern((c, start) => scheduleClip(c, loaded, start))
      return
    }
    playPattern(synth)
  }

  return { ensureLoaded, play }
}

const orderAlert = createClipAlert(orderAlertClipUrl, synthOrderAlert)
const callAlert = createClipAlert(callAlertClipUrl, synthCallAlert)

/** 새 주문 알림음(약 1초, 실제 음원) — 준비 전이면 합성음 "띠디리링↗"(약 1.8초)으로 대신한다 */
export function playOrderAlert() {
  orderAlert.play()
}

/** 직원 호출 알림음(약 1초, 실제 음원) — {@link playOrderAlert}와 다른 클립이라 소음 속에서도 헷갈리지 않는다.
 * 준비 전이면 합성음 "딩동 · 딩동"(약 0.7초)으로 대신한다 */
export function playCallAlert() {
  callAlert.play()
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
  for (const source of scheduledSources) {
    try {
      source.disconnect()
      source.stop()
    } catch {
      // 이미 끝난 소리 — 무시
    }
  }
  scheduledSources.clear()
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
