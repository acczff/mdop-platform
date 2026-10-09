import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import WorkspaceShell from '../components/WorkspaceShell.vue'

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
async function setup(
  narrow = false,
  authorities = ['ROLE_ADMIN'],
  path = '/catalog',
) {
  const media = {
    matches: narrow,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => media),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(path)
  const view = mount(WorkspaceShell, {
    attachTo: document.body,
    props: { authorities, username: 'reader', busy: false },
    slots: { default: '业务内容' },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { view, media, router }
}
it('filters whole navigation groups without granting legacy admin IAM access', async () => {
  const { view } = await setup(false, ['iam:manage'], '/users')
  expect(view.findAll('.nav-group')).toHaveLength(1)
  expect(view.get('a[href="/users"]').isVisible()).toBe(true)
  expect(view.find('a[href="/catalog"]').exists()).toBe(false)
  await view.setProps({ authorities: ['ROLE_ADMIN'] })
  expect(view.find('a[href="/users"]').exists()).toBe(false)
  expect(view.find('a[href="/messages"]').exists()).toBe(true)
  view.unmount()
})
it('opens the active group on direct navigation and browser history', async () => {
  const { view, router } = await setup()
  expect(view.get('a[href="/catalog"]').isVisible()).toBe(true)
  expect(view.get('a[href="/inventory"]').isVisible()).toBe(false)
  await router.push('/inventory')
  await flushPromises()
  expect(view.get('a[href="/inventory"]').isVisible()).toBe(true)
  expect(view.get('a[href="/catalog"]').isVisible()).toBe(false)
  expect(view.get('a[href="/inventory"]').attributes('aria-current')).toBe(
    'page',
  )
  router.back()
  await flushPromises()
  expect(view.get('a[href="/catalog"]').attributes('aria-current')).toBe('page')
  view.unmount()
})
it('keeps one group open and exposes single-item groups as direct links', async () => {
  const { view } = await setup()
  expect(view.find('button[aria-controls="navigation-group-4"]').exists()).toBe(
    false,
  )
  expect(view.get('a[href="/sales"]').isVisible()).toBe(true)
  const inventory = view
    .findAll('.nav-group-toggle')
    .find((button) => button.text() === '库存作业')!
  await inventory.trigger('click')
  expect(view.get('a[href="/inventory"]').isVisible()).toBe(true)
  expect(view.get('a[href="/catalog"]').isVisible()).toBe(false)
  await inventory.trigger('click')
  expect(view.get('a[href="/inventory"]').isVisible()).toBe(false)
  view.unmount()
})
it('defaults to content on narrow screens and closes navigation after a link', async () => {
  const { view } = await setup(true)
  const toggle = view.get('button[aria-controls="workspace-navigation"]')
  expect(view.get('aside').isVisible()).toBe(false)
  await toggle.trigger('click')
  await flushPromises()
  expect(view.get('aside').isVisible()).toBe(true)
  expect(document.activeElement).toBe(view.get('.nav-group-toggle').element)
  expect(view.get('.workspace').isVisible()).toBe(false)
  await view.get('a[href="/warehouses"]').trigger('click')
  await flushPromises()
  expect(view.get('aside').isVisible()).toBe(false)
  expect(view.get('.workspace').isVisible()).toBe(true)
  expect(document.activeElement).toBe(view.get('.workspace').element)
  view.unmount()
})
it('restores focus to the navigation control on Escape and breakpoint changes', async () => {
  const { view, media } = await setup()
  await view.get('aside').trigger('keydown', { key: 'Escape' })
  await flushPromises()
  const toggle = view.get('button[aria-controls="workspace-navigation"]')
  expect(document.activeElement).toBe(toggle.element)
  media.matches = true
  media.addEventListener.mock.calls[0]![1]()
  await flushPromises()
  expect(toggle.attributes('aria-expanded')).toBe('false')
  expect(view.get('.workspace').isVisible()).toBe(true)
  view.unmount()
  expect(media.removeEventListener).toHaveBeenCalled()
})

it('closes the account menu with Escape and returns focus to its summary', async () => {
  const { view } = await setup()
  const menu = view.get('details')
  ;(menu.element as HTMLDetailsElement).open = true
  const logout = menu.get('button')
  ;(logout.element as HTMLButtonElement).focus()
  await logout.trigger('keydown', { key: 'Escape' })
  expect((menu.element as HTMLDetailsElement).open).toBe(false)
  expect(document.activeElement).toBe(menu.get('summary').element)
  view.unmount()
})
