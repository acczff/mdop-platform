import { afterEach, expect, it, vi } from 'vitest'
import { request } from '../api'
afterEach(() => vi.unstubAllGlobals())
it('distinguishes failed reads from an unknown write result', async () => {
  const fetch = vi.fn().mockRejectedValue(new Error('offline'))
  vi.stubGlobal('fetch', fetch)
  await expect(request('/api/example')).rejects.toThrow('读取失败或请求超时')
  fetch.mockResolvedValueOnce(
    new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'test' })),
  )
  await expect(
    request('/api/example', { method: 'POST', body: '{}' }),
  ).rejects.toThrow('写入结果可能已生效')
})
