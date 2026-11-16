package io.github.acczff.mdop.wms.receiving.application;

import static io.github.acczff.mdop.wms.receiving.application.ReceivingModels.*;

import java.util.List;
import java.util.Optional;

/**
 * 收货域的只读查询端口。
 *
 * <p>由使用方（应用层）声明、由 {@code infrastructure} 实现：应用服务只表达“需要什么事实”，
 * 不再关心表名、列名与连接方式。返回类型是应用层的读模型，避免领域层依赖序列化注解。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>所有语句使用显式列名，不用 {@code SELECT *}，表结构新增列不会悄悄改变返回结构；
 *   <li>{@code lock} 参数为真时使用 {@code FOR UPDATE}，调用方必须先获得仓库锁，
 *       以保证“仓库 → 单据 → 余额”的加锁顺序不被破坏；
 *   <li>本端口不写数据、不开启事务，事务边界始终由应用层决定。
 * </ul>
 */
public interface ReceivingQueries {

    /** 按仓库列出最近的到货通知（最多 100 条）。 */
    List<Arrival> arrivals(long warehouseId);

    /** 列出到货通知的明细行。 */
    List<ArrivalItem> arrivalItems(long arrivalId);

    /** 读取到货通知；{@code lock=true} 时加行锁。 */
    Optional<Arrival> findArrival(long id, boolean lock);

    /** 按到货通知列出收货单（最多 100 条）。 */
    List<Receipt> receipts(long arrivalId);

    /** 读取收货单；{@code lock=true} 时加行锁。 */
    Optional<Receipt> findReceipt(long id, boolean lock);

    /** 按创建幂等键查收货单，并加行锁；用于识别重复建单。 */
    Optional<Receipt> lockReceiptByCreationKey(String creationKey);

    /** 列出收货单明细。 */
    List<Item> items(long receiptId);

    /** 按主键读取单条收货明细。 */
    Optional<Item> findItem(long id);

    /** 校验明细行是否属于指定到货通知。 */
    Optional<ArrivalItem> findArrivalItem(long id, long arrivalId);

    /** 统计到货通知中尚未收满的明细行数，用于推进通知状态。 */
    int countRemainingArrivalLines(long arrivalId);

    /** 列出仓库下的库存余额（最多 {@code limit} 条）。 */
    List<Balance> balances(long warehouseId, int limit);

    /** 按库存键读取余额并加行锁；调用方负责保证锁顺序。 */
    Optional<Balance> lockBalance(String stockKey);

    /** 列出某张收货单产生的库存流水。 */
    List<Ledger> ledger(long receiptId);
}
