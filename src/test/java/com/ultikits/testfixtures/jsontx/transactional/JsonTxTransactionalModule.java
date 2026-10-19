package com.ultikits.testfixtures.jsontx.transactional;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.interfaces.DataOperator;

/**
 * A module that uses {@code @Transactional} on {@code datasource.type: json}, for
 * {@code JsonTransactionalModuleTest} (Phase 20.4, plan 10). Its service writes two of the
 * module's tables in one transactional method.
 * <p>
 * The module scans only this package. Its main class does not {@code @Autowired} the
 * transactional service, so the service is created by the container's {@code refresh()}, as an
 * ordinary module service is. Every class the module needs is a static nested class of this one,
 * so the test can package the whole module into one JAR.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.jsontx.transactional")
public class JsonTxTransactionalModule extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** The first table: one order per id. */
    @Table("jsontx_orders")
    public static class OrderRow extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("item")
        private String item;

        /** For deserialization. */
        public OrderRow() {
        }

        /**
         * @param id   the order id
         * @param item the ordered item
         */
        public OrderRow(String id, String item) {
            setId(id);
            this.item = item;
        }

        /** @return the ordered item */
        public String getItem() {
            return item;
        }
    }

    /** The second table: one ledger entry per order id. */
    @Table("jsontx_ledger")
    public static class LedgerRow extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("entry")
        private String entry;

        /** For deserialization. */
        public LedgerRow() {
        }

        /**
         * @param id    the order id the entry belongs to
         * @param entry the ledger text
         */
        public LedgerRow(String id, String entry) {
            setId(id);
            this.entry = entry;
        }

        /** @return the ledger text */
        public String getEntry() {
            return entry;
        }
    }

    /**
     * The module's service. Its operators are taken once, in {@code @PostConstruct}, the way a
     * module service usually holds them; the transactional methods then run on any thread.
     */
    @Service
    public static class OrderService {

        @Autowired
        private UltiToolsPlugin plugin;

        private DataOperator<OrderRow> orders;
        private DataOperator<LedgerRow> ledger;

        @PostConstruct
        void openOperators() {
            orders = plugin.getDataOperator(OrderRow.class);
            ledger = plugin.getDataOperator(LedgerRow.class);
        }

        /**
         * Writes an order and its ledger entry as one transaction.
         *
         * @param id            the order id
         * @param item          the ordered item
         * @param betweenWrites runs after the order is written and before the ledger entry is
         */
        @Transactional
        public void placeOrder(String id, String item, Runnable betweenWrites) {
            orders.insert(new OrderRow(id, item));
            betweenWrites.run();
            ledger.insert(new LedgerRow(id, "charged " + item));
        }

        /**
         * Writes an order and its ledger entry, then fails, so the transaction rolls back.
         *
         * @param id   the order id
         * @param item the ordered item
         */
        @Transactional
        public void placeOrderThenFail(String id, String item) {
            orders.insert(new OrderRow(id, item));
            ledger.insert(new LedgerRow(id, "charged " + item));
            throw new IllegalStateException("payment refused for " + id);
        }

        /**
         * @param id the order id
         * @return the order's item, or {@code null} when no order is stored under {@code id}
         */
        public String orderItem(String id) {
            OrderRow row = orders.getById(id);
            return row == null ? null : row.getItem();
        }

        /**
         * @param id the order id
         * @return the ledger text, or {@code null} when no entry is stored under {@code id}
         */
        public String ledgerEntry(String id) {
            LedgerRow row = ledger.getById(id);
            return row == null ? null : row.getEntry();
        }
    }
}
