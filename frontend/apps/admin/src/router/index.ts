import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/sales-orders',
      component: () => import('../views/SalesOrdersView.vue'),
    },
    {
      path: '/purchasing',
      component: () => import('../views/PurchasingView.vue'),
    },
    { path: '/users', component: () => import('../views/UsersView.vue') },
    { path: '/account', component: () => import('../views/AccountView.vue') },
    { path: '/freezes', component: () => import('../views/FreezesView.vue') },
    {
      path: '/cross-transfers',
      component: () => import('../views/CrossTransfersView.vue'),
    },
    { path: '/sales', component: () => import('../views/SalesView.vue') },
    {
      path: '/finished-goods',
      component: () => import('../views/FinishedGoodsView.vue'),
    },
    {
      path: '/production',
      component: () => import('../views/ProductionView.vue'),
    },
    { path: '/', redirect: '/account' },
    { path: '/issues', component: () => import('../views/IssuesView.vue') },
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
    { path: '/:pathMatch(.*)*', redirect: '/account' },
  ],
})

export default router
