package io.github.acczff.mdop.purchasing.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 采购单据的读取入口。
 *
 * <p>应用层只依赖本接口，不关心 SQL、列名或数据库类型；实现位于
 * {@code infrastructure}。返回结构沿用现有接口契约的字段名，避免在分层改造中改变
 * HTTP 输出。
 *
 * <p>写操作（插入、状态更新）仍由应用层持有事务与幂等语义，本接口只负责查询与加锁读取。
 */
public interface PurchaseRepository {

    /** 按主键读取单据头；不存在时返回空。 */
    Optional<Map<String, Object>> findDocument(long id);

    /** 在事务内以行锁读取单据头；调用方需先锁定仓库。 */
    Map<String, Object> lockDocument(long id);

    /** 读取单据行，按物料排序。 */
    List<Map<String, Object>> findLines(long documentId);

    /** 统计指定仓库与类型的单据数量。 */
    long countDocuments(long warehouseId, String kind);

    /** 分页读取单据头，按主键倒序。 */
    List<Map<String, Object>> findDocumentPage(
            long warehouseId, String kind, int size, long offset);

    /** 读取属于指定订单的到货安排；不存在时返回空。 */
    Optional<Map<String, Object>> findArrangement(long orderId, long arrangementId);

    /** 读取到货安排行及其订单行快照。 */
    List<Map<String, Object>> findArrangementLines(long arrangementId);

    /** 读取订单下的全部到货安排，按主键倒序。 */
    List<Map<String, Object>> findArrangements(long orderId);
}
