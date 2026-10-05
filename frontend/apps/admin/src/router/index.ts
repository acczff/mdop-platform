import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/warehouses' },
    {
      path: '/warehouses',
      component: () => import('../views/WarehousesView.vue'),
    },
    { path: '/catalog', component: () => import('../views/CatalogView.vue') },
    {
      path: '/receiving',
      component: () => import('../views/ReceivingView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/warehouses' },
  ],
})

export default router
