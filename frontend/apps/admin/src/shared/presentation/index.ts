/** Display only: never use these values for calculations or write payloads. */
export function quantity(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const text = String(value)
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) return text
  const [whole = '', decimal = ''] = text.split('.')
  const fraction = decimal.replace(/0+$/, '')
  const result = whole + (fraction ? `.${fraction}` : '')
  return /^-0(?:\.0*)?$/.test(result) ? '0' : result
}

/** Business dates have no timezone. Reject timestamps rather than shift the day. */
export function businessDate(value: string | null | undefined): string {
  if (!value) return '—'
  return /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : '日期待核对'
}

export function timestamp(
  value: string | null | undefined,
  inlineTimezone = true,
): string {
  if (!value) return '—'
  // An offset-free value has no reliable timezone; do not guess one.
  if (!/(Z|[+-]\d{2}:?\d{2})$/i.test(value)) {
    const local = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.\d+)?$/.exec(
      value,
    )
    return `${local ? `${local[1]} ${local[2]}` : value}${inlineTimezone ? '（时区未提供）' : ''}`
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间待核对'
  return (
    new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    })
      .format(date)
      .replace(/\//g, '-') + '（北京时间）'
  )
}

export function identity(name?: string | null, code?: string | null): string {
  if (name && code && name !== code) return `${name}（${code}）`
  return name || code || '名称未提供'
}

/** Keep existing references stable. The Chinese type distinguishes ERP and WMS. */
export function documentRef(
  type: string,
  number: string | number | null | undefined,
): string {
  return number === null || number === undefined || number === ''
    ? `${type}（编号未提供）`
    : `${type} ${number}`
}
