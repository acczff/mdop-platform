/** Decimal strings only: six-place quantity/price, two-place HALF_UP line amount. */
export function lineAmount(quantity: string, price: string): string {
  function scaled(value: string) {
    if (!/^\d{1,12}(\.\d{1,6})?$/.test(value)) return undefined
    const [whole, fraction = ''] = value.split('.')
    return BigInt(whole!) * 1000000n + BigInt(fraction.padEnd(6, '0'))
  }
  const q = scaled(quantity),
    p = scaled(price)
  if (q === undefined || p === undefined) return ''
  const cents = (q * p + 5000000000n) / 10000000000n
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`
}
