export const navigation = [
  {
    name: '基础设置',
    items: [
      { path: '/catalog', name: '基础资料', permission: 'warehouse:read' },
      { path: '/warehouses', name: '仓库管理', permission: 'warehouse:read' },
    ],
  },
  {
    name: '采购收货',
    items: [
      {
        path: '/purchasing',
        name: '采购需求与订单',
        permission: 'purchasing:read',
        legacyAdmin: false,
      },
      { path: '/receiving', name: '采购收货', permission: 'wms:arrival:read' },
      { path: '/quality', name: '质检与上架', permission: 'wms:quality:read' },
      {
        path: '/purchase-returns',
        name: '不合格品退货',
        permission: 'wms:return:read',
      },
      {
        path: '/corrections',
        name: '差异与冲正',
        permission: 'wms:correction:read',
      },
    ],
  },
  {
    name: '库存作业',
    items: [
      {
        path: '/inventory',
        name: '库存与追溯',
        permission: 'wms:inventory:read',
      },
      { path: '/transfers', name: '仓内移库', permission: 'wms:transfer:read' },
      {
        path: '/cross-transfers',
        name: '跨仓调拨',
        permission: 'wms:cross-transfer:read',
      },
      { path: '/counts', name: '库存盘点', permission: 'wms:count:read' },
      { path: '/freezes', name: '库存冻结', permission: 'wms:freeze:read' },
    ],
  },
  {
    name: '生产协同',
    items: [
      {
        path: '/boms',
        name: 'BOM 版本',
        permission: 'bom:read',
        legacyAdmin: false,
      },
      { path: '/issues', name: '生产领料', permission: 'wms:issue:read' },
      {
        path: '/production',
        name: '生产消耗与退料',
        permission: 'wms:production:read',
      },
      {
        path: '/finished-goods',
        name: '成品入库',
        permission: 'wms:finished:read',
      },
    ],
  },
  {
    name: '销售出库',
    items: [
      {
        path: '/sales-orders',
        name: '销售订单',
        permission: 'sales:read',
        legacyAdmin: false,
      },
      { path: '/sales', name: '销售出库', permission: 'wms:sales:read' },
    ],
  },
  {
    name: '系统管理',
    items: [
      {
        path: '/users',
        name: '用户与权限',
        permission: 'iam:manage',
        legacyAdmin: false,
      },
      {
        path: '/messages',
        name: '消息管理',
        permission: 'integration:simulate',
      },
    ],
  },
]
