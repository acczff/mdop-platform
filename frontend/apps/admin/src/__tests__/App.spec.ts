import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import App from '../App.vue'

describe('App', () => {
  it('renders the MDOP administration shell', () => {
    const wrapper = mount(App)

    expect(wrapper.get('h1').text()).toBe('MDOP 管理端')
    expect(wrapper.text()).toContain('I0 前端工程基线已就绪。')
  })
})
