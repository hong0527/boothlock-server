/**
 * 회원가입(Figma 237:344, 임시 데모) 노출 여부 — 신원 확인 없이 누구나 부스를 만들 수 있어
 * 기본은 꺼짐. 시연·심사 때만 VITE_SIGNUP_ENABLED=true로 켠다.
 * 백엔드도 같은 이유로 boothlock.signup.enabled(기본 false)로 따로 막혀 있다(BoothSignupService 참고) —
 * 이 값은 화면 노출만 제어할 뿐, 실제 보안 경계는 백엔드 쪽이다.
 */
export const SIGNUP_ENABLED = import.meta.env.VITE_SIGNUP_ENABLED === 'true'
