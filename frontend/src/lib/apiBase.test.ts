import { describe, expect, it } from 'vitest'
import { apiUrl, assetUrl, normalizeBase } from './apiBase'

describe('normalizeBase', () => {
  it('비어 있거나 문자열이 아니면 빈 문자열(같은 오리진)', () => {
    expect(normalizeBase(undefined)).toBe('')
    expect(normalizeBase('')).toBe('')
    expect(normalizeBase('   ')).toBe('')
  })

  it('끝의 슬래시를 떼어 경로와 이중 슬래시가 안 나게 한다', () => {
    expect(normalizeBase('https://api.example.com/')).toBe('https://api.example.com')
    expect(normalizeBase('https://api.example.com//')).toBe('https://api.example.com')
  })
})

describe('apiUrl', () => {
  it('베이스가 없으면 상대 경로 그대로 (개발 프록시·같은 오리진)', () => {
    expect(apiUrl('/api/v1/menus', '')).toBe('/api/v1/menus')
  })

  it('베이스가 있으면 앞에 붙인다', () => {
    expect(apiUrl('/api/v1/menus', 'https://api.example.com')).toBe('https://api.example.com/api/v1/menus')
    expect(apiUrl('api/v1/menus', 'https://api.example.com')).toBe('https://api.example.com/api/v1/menus')
  })

  it('이미 절대 URL이면 건드리지 않는다', () => {
    expect(apiUrl('https://other.example.com/x', 'https://api.example.com')).toBe('https://other.example.com/x')
  })
})

describe('assetUrl', () => {
  it('없는 값은 undefined (img src 미지정)', () => {
    expect(assetUrl(null, 'https://api.example.com')).toBeUndefined()
    expect(assetUrl(undefined, 'https://api.example.com')).toBeUndefined()
    expect(assetUrl('', 'https://api.example.com')).toBeUndefined()
  })

  it('서버 상대 주소(/uploads/…)에만 베이스를 붙인다', () => {
    expect(assetUrl('/uploads/menu/a.jpg', 'https://api.example.com')).toBe('https://api.example.com/uploads/menu/a.jpg')
    expect(assetUrl('/uploads/menu/a.jpg', '')).toBe('/uploads/menu/a.jpg')
  })

  it('blob:·data:·절대 URL은 그대로', () => {
    expect(assetUrl('blob:http://localhost/123', 'https://api.example.com')).toBe('blob:http://localhost/123')
    expect(assetUrl('data:image/png;base64,AAA', 'https://api.example.com')).toBe('data:image/png;base64,AAA')
    expect(assetUrl('https://cdn.example.com/a.png', 'https://api.example.com')).toBe('https://cdn.example.com/a.png')
  })
})
