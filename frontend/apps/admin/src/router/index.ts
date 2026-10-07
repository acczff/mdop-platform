import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/warehouses' },
    { path: '/counts', component: () => import('../views/CountsView.vue') },
    {
      path: '/warehouses',
      component: () => import('../views/WarehousesView.vue'),
    },
    { path: '/catalog', component: () => import('../views/CatalogView.vue') },
    { path: '/messages', component: () => import('../views/MessagesView.vue') },
    { path: '/quality', component: () => import('../views/QualityView.vue') },
    {
      path: '/transfers',
      component: () => import('../views/TransfersView.vue'),
    },
    {
      path: '/inventory',
      component: () => import('../views/InventoryView.vue'),
    },
    {
      path: '/purchase-returns',
      component: () => import('../views/PurchaseReturnsView.vue'),
    },
    {
      path: '/corrections',
      component: () => import('../views/CorrectionsView.vue'),
    },
    {
      path: '/receiving',
      component: () => import('../views/ReceivingView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/warehouses' },
  ],
})

export default router
