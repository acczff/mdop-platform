/** Visual emphasis only; labels, permissions and business transitions remain authoritative. */
export function statusTone(status: string) {
  if (
    ['CLOSED', 'CONVERTED', 'DELIVERED', 'SHIPPED', 'COMPLETED'].includes(
      status,
    )
  )
    return 'complete'
  if (['APPROVED', 'FULFILLING', 'QUALIFIED'].includes(status)) return 'active'
  if (['SUBMITTED', 'PENDING', 'PENDING_INSPECTION'].includes(status))
    return 'waiting'
  if (status === 'REJECTED') return 'rejected'
  return 'neutral'
}
