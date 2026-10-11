import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import OperationRecord from '../components/OperationRecord.vue'
import OperationTimeNote from '../components/OperationTimeNote.vue'

it('labels accounts without inventing names or roles, retaining account when a name is available', async () => {
  const v = mount(OperationRecord, {
    props: { title: '实物收货', account: 'warehouse01' },
  })
  expect(v.text()).toContain('操作账号warehouse01')
  expect(v.text()).not.toContain('仓管')
  await v.setProps({ name: '张三' })
  expect(v.text()).toContain('操作人张三（warehouse01）')
  await v.setProps({ name: null, account: null })
  expect(v.text()).toContain('操作账号未记录')
  expect(v.text()).toContain('操作时间未记录')
})

it('keeps locations, codes and long multiline remarks independently labelled and escaped', () => {
  const note = '包装破损，需要复检。'.repeat(30) + '\n<script>unsafe</script>'
  const v = mount(OperationRecord, {
    props: {
      title: '质检判定',
      result: '不合格',
      location: '隔离区',
      locationCode: 'HOLD',
      note,
      noteLabel: '判定备注',
    },
  })
  expect(v.get('small').text()).toBe('库位编码：HOLD')
  expect(v.get('.operation-note').text()).toBe(note)
  expect(v.find('script').exists()).toBe(false)
  expect(v.text()).toContain('判定备注')
})

it('preserves mixed timezone meaning with a single shared explanation', () => {
  const v = mount({
    components: { OperationRecord, OperationTimeNote },
    template: `<section><OperationRecord title="收货" at="2026-10-11T18:31:15" /><OperationRecord title="上架" at="2026-10-11T10:31:49Z" /><OperationTimeNote /></section>`,
  })
  expect(v.findAll('.operation-time-note')).toHaveLength(1)
  const records = v.findAll('article')
  expect(records[0]!.text()).toContain('2026-10-11 18:31:15')
  expect(records[0]!.text()).not.toContain('北京时间')
  expect(records[0]!.text()).not.toContain('时区未提供')
  expect(records[1]!.text()).toContain('2026-10-11 18:31:49（北京时间）')
})
