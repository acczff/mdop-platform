import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RecordTabs from '../components/RecordTabs.vue'

it('keeps disabled details locked for both pointer and keyboard navigation', async () => {
  const view = mount(RecordTabs, {
    props: {
      modelValue: 'a',
      prefix: 'qa',
      disabled: true,
      tabs: [
        { id: 'a', label: '概述' },
        { id: 'b', label: '记录' },
      ],
    },
  })
  await view.get('#qa-tab-b').trigger('click')
  await view.get('#qa-tab-a').trigger('keydown', { key: 'ArrowRight' })
  expect(view.emitted('update:modelValue')).toBeUndefined()
  await view.setProps({ disabled: false })
  await view.get('#qa-tab-a').trigger('keydown', { key: 'End' })
  expect(view.emitted('update:modelValue')).toEqual([['b']])
  view.unmount()
})
