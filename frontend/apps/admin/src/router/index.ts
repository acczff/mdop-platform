import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/finance',
      component: () => import('../modules/finance/views/FinanceView.vue'),
    },
    {
      path: '/production-orders',
      component: () =>
        import('../modules/manufacturing/views/ProductionOrdersView.vue'),
    },
    {
      path: '/boms',
      component: () => import('../modules/manufacturing/views/BomsView.vue'),
    },
    {
      path: '/sales-orders',
      component: () => import('../modules/sales/views/SalesOrdersView.vue'),
    },
    {
      path: '/purchasing',
      component: () => import('../modules/purchasing/views/PurchasingView.vue'),
    },
    {
      path: '/users',
      component: () => import('../modules/system/views/UsersView.vue'),
    },
    {
      path: '/account',
      component: () => import('../modules/system/views/AccountView.vue'),
    },
    {
      path: '/freezes',
      component: () => import('../modules/inventory/views/FreezesView.vue'),
    },
    {
      path: '/cross-transfers',
      component: () =>
        import('../modules/inventory/views/CrossTransfersView.vue'),
    },
    {
      path: '/sales',
      component: () => import('../modules/sales/views/SalesView.vue'),
    },
    {
      path: '/finished-goods',
      component: () =>
        import('../modules/manufacturing/views/FinishedGoodsView.vue'),
    },
    {
      path: '/production',
      component: () =>
        import('../modules/manufacturing/views/ProductionView.vue'),
    },
    { path: '/', redirect: '/account' },
    {
      path: '/issues',
      component: () => import('../modules/manufacturing/views/IssuesView.vue'),
    },
    {
      path: '/counts',
      component: () => import('../modules/inventory/views/CountsView.vue'),
    },
    {
      path: '/warehouses',
      component: () => import('../modules/masterdata/views/WarehousesView.vue'),
    },
    {
      path: '/catalog',
      component: () => import('../modules/masterdata/views/CatalogView.vue'),
    },
    {
      path: '/messages',
      component: () => import('../modules/system/views/MessagesView.vue'),
    },
    {
      path: '/quality',
      component: () => import('../modules/receiving/views/QualityView.vue'),
    },
    {
      path: '/transfers',
      component: () => import('../modules/inventory/views/TransfersView.vue'),
    },
    {
      path: '/inventory',
      component: () => import('../modules/inventory/views/InventoryView.vue'),
    },
    {
      path: '/purchase-returns',
      component: () =>
        import('../modules/receiving/views/PurchaseReturnsView.vue'),
    },
    {
      path: '/corrections',
      component: () => import('../modules/receiving/views/CorrectionsView.vue'),
    },
    {
      path: '/receiving',
      component: () => import('../modules/receiving/views/ReceivingView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/account' },
  ],
})

export default router
