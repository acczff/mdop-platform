import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import App from '../App.vue'
import { ApiError, request, refreshCsrf } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
  refreshCsrf: vi.fn(),
}))
afterEach(() => vi.resetAllMocks())

describe('App', () => {
  it('restores an anonymous session and logs in with form credentials', async () => {
    vi.mocked(request).mockRejectedValueOnce(new ApiError(401, 'unauthorized'))
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('登录工作台')
    await wrapper.get('input[name="password"]').setValue('test-password')
    vi.mocked(request)
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({ username: 'admin', authorities: ['ROLE_ADMIN'] })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: expect.any(URLSearchParams),
      }),
    )
    expect(refreshCsrf).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('退出登录')
    window.dispatchEvent(new Event('session-expired'))
    await flushPromises()
    expect(wrapper.text()).toContain('登录已过期')
    wrapper.unmount()
  })
})
