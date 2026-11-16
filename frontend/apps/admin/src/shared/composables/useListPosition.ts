import { nextTick, ref } from 'vue'

/** Preserve the list's viewport and keyboard position without reloading data. */
export function useListPosition() {
  const pageRoot = ref<HTMLElement>()
  let scrollTop = 0
  let trigger: HTMLElement | null = null
  function rememberList() {
    scrollTop =
      pageRoot.value?.closest<HTMLElement>('.workspace')?.scrollTop || 0
    trigger =
      document.activeElement instanceof HTMLElement &&
      pageRoot.value?.contains(document.activeElement)
        ? document.activeElement
        : null
  }
  async function focusDetail() {
    await nextTick()
    pageRoot.value?.querySelector<HTMLButtonElement>('.back-to-list')?.focus()
  }
  async function restoreList() {
    await nextTick()
    const workspace = pageRoot.value?.closest<HTMLElement>('.workspace')
    if (workspace) workspace.scrollTop = scrollTop
    if (trigger?.isConnected) trigger.focus({ preventScroll: true })
  }
  return { pageRoot, rememberList, focusDetail, restoreList }
}
