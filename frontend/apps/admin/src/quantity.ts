// Inventory quantities use six decimal places. Never pass them through Number.
export function scaled(value: string | number): bigint {
  const [whole = '0', fraction = ''] = String(value).split('.')
  return BigInt(whole) * 1000000n + BigInt(fraction.padEnd(6, '0'))
}
export function remaining(
  notice: string | number,
  received: string | number,
): string {
  const value = scaled(notice) - scaled(received)
  const decimals = (value % 1000000n)
    .toString()
    .padStart(6, '0')
    .replace(/0+$/, '')
  return `${value / 1000000n}${decimals ? `.${decimals}` : ''}`
}
