// Inventory quantities use six decimal places. Never pass them through Number.
const SCALE = 1000000n
const FRACTION_DIGITS = 6

/**
 * 把十进制字符串转成定点整数（1e6）。
 *
 * 显式处理符号：`BigInt('-0')` 会得到 0，直接沿用会丢掉负号。
 * 小数超过 6 位时截断，不做四舍五入——后端已限定 6 位，超出说明数据异常。
 */
export function scaled(value: string | number): bigint {
  const text = String(value).trim()
  const negative = text.startsWith('-')
  const unsigned = negative ? text.slice(1) : text
  const [whole = '0', fraction = ''] = unsigned.split('.')
  const magnitude =
    BigInt(whole || '0') * SCALE +
    BigInt(fraction.padEnd(FRACTION_DIGITS, '0').slice(0, FRACTION_DIGITS))
  return negative ? -magnitude : magnitude
}

/** 剩余数量 = 通知数量 - 已收数量，保留至多 6 位小数，负值带前导负号。 */
export function remaining(
  notice: string | number,
  received: string | number,
): string {
  const value = scaled(notice) - scaled(received)
  const negative = value < 0n
  const magnitude = negative ? -value : value
  const decimals = (magnitude % SCALE)
    .toString()
    .padStart(FRACTION_DIGITS, '0')
    .replace(/0+$/, '')
  return `${negative ? '-' : ''}${magnitude / SCALE}${decimals ? `.${decimals}` : ''}`
}
