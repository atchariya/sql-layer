/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.test.pt;

import com.foundationdb.qp.operator.ExpressionGenerator;
import com.foundationdb.qp.operator.StoreAdapter;
import com.foundationdb.server.service.servicemanager.GuicedServiceManager.BindingsConfigurationProvider;
import com.foundationdb.server.store.PersistitStore;
import com.foundationdb.server.test.ApiTestBase;

import com.foundationdb.ais.model.TableIndex;
import com.foundationdb.qp.expression.IndexBound;
import com.foundationdb.qp.expression.IndexKeyRange;
import com.foundationdb.qp.expression.RowBasedUnboundExpressions;
import com.foundationdb.qp.operator.API;
import com.foundationdb.qp.operator.Cursor;
import com.foundationdb.qp.operator.LeafCursor;
import com.foundationdb.qp.operator.Operator;
import com.foundationdb.qp.operator.OperatorCursor;
import com.foundationdb.qp.operator.QueryBindings;
import com.foundationdb.qp.operator.QueryBindingsCursor;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.row.ValuesHolderRow;
import com.foundationdb.qp.row.ValuesRow;
import com.foundationdb.qp.rowtype.DerivedTypesSchema;
import com.foundationdb.qp.rowtype.IndexRowType;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.qp.rowtype.Schema;
import com.foundationdb.qp.rowtype.ValuesRowType;
import com.foundationdb.server.api.dml.SetColumnSelector;
import com.foundationdb.server.error.QueryCanceledException;
import com.foundationdb.server.explain.CompoundExplainer;
import com.foundationdb.server.explain.ExplainContext;
import com.foundationdb.server.expression.Expression;
import com.foundationdb.server.expression.ExpressionComposer;
import com.foundationdb.server.expression.std.Expressions;
import com.foundationdb.server.expression.std.ExpressionTypes;
import com.foundationdb.server.service.functions.FunctionsRegistry;
import com.foundationdb.server.service.functions.FunctionsRegistryImpl;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.test.ExpressionGenerators;
import com.foundationdb.server.test.it.PersistitITBase;
import com.foundationdb.server.types.AkType;
import com.foundationdb.server.types.ValueSource;
import com.foundationdb.server.types3.TInstance;
import com.foundationdb.server.types3.mcompat.mtypes.MNumeric;

import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.exception.PersistitException;

import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

import static com.foundationdb.server.test.ExpressionGenerators.boundField;
import static com.foundationdb.server.test.ExpressionGenerators.field;

public class AggregatePT extends ApiTestBase {
    public static final int NKEYS = 10;
    public static final int ROW_COUNT = 100000;
    public static final int WARMUPS = 100, REPEATS = 10;

    public AggregatePT() {
        super("PT");
    }

    @Override
    protected BindingsConfigurationProvider serviceBindingsProvider() {
        return PersistitITBase.doBind(super.serviceBindingsProvider());
    }

    @Override
    protected Map<String, String> startupConfigProperties() {
        return uniqueStartupConfigProperties(getClass());
    }

    private PersistitStore persistitStore() {
        return (PersistitStore)store();
    }

    private TableIndex index;

    @Before
    public void loadData() throws Exception {
        transactionally(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        int t = createTable("user", "t",
                             "id INT NOT NULL PRIMARY KEY",
                             "gid INT",
                             "flag BOOLEAN",
                             "sval VARCHAR(20) NOT NULL",
                             "n1 INT",
                             "n2 INT",
                             "k INT");
         Random rand = new Random(69);
         for (int i = 0; i < ROW_COUNT; i++) {
             writeRow(t, i,
                      rand.nextInt(NKEYS),
                      (rand.nextInt(100) < 80) ? 0 : 1,
                      randString(rand, 20),
                      rand.nextInt(100),
                      rand.nextInt(1000),
                      rand.nextInt());
         }
         index = createIndex("user", "t", "t_i", 
                             "gid", "sval", "flag", "k", "n1", "n2", "id");
         return null;
      }});
    }

    private String randString(Random rand, int size) {
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < size; i++) {
            str.append((char)('A' + rand.nextInt(26)));
        }
        return str.toString();
    }

    @Test
    public void normalOperators() {
        Schema schema = new Schema(ais());
        IndexRowType indexType = schema.indexRowType(index);
        IndexKeyRange keyRange = IndexKeyRange.unbounded(indexType);
        API.Ordering ordering = new API.Ordering();
        ordering.append(field(indexType, 0), true);
        
        Operator plan = API.indexScan_Default(indexType, keyRange, ordering);
        RowType rowType = indexType;

        plan = spa(plan, rowType);

        StoreAdapter adapter = newStoreAdapter(schema);
        QueryContext queryContext = queryContext(adapter);
        QueryBindings queryBindings = queryContext.createBindings();
        
        System.out.println("NORMAL OPERATORS");
        double time = 0.0;
        for (int i = 0; i < WARMUPS+REPEATS; i++) {
            long start = System.nanoTime();
            Cursor cursor = API.cursor(plan, queryContext, queryBindings);
            cursor.openTopLevel();
            while (true) {
                Row row = cursor.next();
                if (row == null) break;
                if (i == 0) System.out.println(row);
            }
            cursor.closeTopLevel();
            long end = System.nanoTime();
            if (i >= WARMUPS)
                time += (end - start) / 1.0e6;
        }
        System.out.println(String.format("%g ms", time / REPEATS));
    }

    private Operator spa(Operator plan, RowType rowType) {
        FunctionsRegistry functions = new FunctionsRegistryImpl();
        ExpressionComposer and = functions.composer("and");
        Expression pred1 = functions.composer("greaterOrEquals")
            .compose(Arrays.asList(Expressions.field(rowType, 1),
                                   Expressions.literal("M")),
                     Arrays.asList(ExpressionTypes.varchar(20),
                                   ExpressionTypes.varchar(1),
                                   ExpressionTypes.BOOL));
        Expression pred2 = functions.composer("lessOrEquals")
            .compose(Arrays.asList(Expressions.field(rowType, 1),
                                   Expressions.literal("Y")),
                     Arrays.asList(ExpressionTypes.varchar(20),
                                   ExpressionTypes.varchar(1),
                                   ExpressionTypes.BOOL));
        Expression pred = and.compose(Arrays.asList(pred1, pred2), 
                                      Arrays.asList(ExpressionTypes.BOOL, ExpressionTypes.BOOL, ExpressionTypes.BOOL));
        pred2 = functions.composer("notEquals")
            .compose(Arrays.asList(Expressions.field(rowType, 2),
                                   Expressions.literal(1L)),
                     Arrays.asList(ExpressionTypes.LONG,
                                   ExpressionTypes.LONG,
                                   ExpressionTypes.BOOL));
        pred = and.compose(Arrays.asList(pred, pred2),
                           Arrays.asList(ExpressionTypes.BOOL, ExpressionTypes.BOOL, ExpressionTypes.BOOL));
        
        plan = API.select_HKeyOrdered(plan, rowType, pred);
        plan = API.project_Default(plan, rowType,
                                   Arrays.asList(ExpressionGenerators.field(rowType, 0),
                                           ExpressionGenerators.field(rowType, 3),
                                           ExpressionGenerators.field(rowType, 4),
                                           ExpressionGenerators.field(rowType, 5)));
        rowType = plan.rowType();
        plan = API.aggregate_Partial(plan, rowType, 
                                     1, functions,
                                     Arrays.asList("count", "sum", "sum"),
                                     new ArrayList<>(3));
        return plan;
    }

    @Test
    public void bespokeOperator() {
        Schema schema = new Schema(ais());
        IndexRowType indexType = schema.indexRowType(index);
        IndexKeyRange keyRange = IndexKeyRange.unbounded(indexType);
        API.Ordering ordering = new API.Ordering();
        ordering.append(field(indexType, 0), true);
        
        Operator plan = API.indexScan_Default(indexType, keyRange, ordering);
        RowType rowType = indexType;

        plan = new BespokeOperator(plan);

        StoreAdapter adapter = newStoreAdapter(schema);
        QueryContext queryContext = queryContext(adapter);
        QueryBindings queryBindings = queryContext.createBindings();
        
        System.out.println("BESPOKE OPERATOR");
        double time = 0.0;
        for (int i = 0; i < WARMUPS+REPEATS; i++) {
            long start = System.nanoTime();
            Cursor cursor = API.cursor(plan, queryContext, queryBindings);
            cursor.openTopLevel();
            while (true) {
                Row row = cursor.next();
                if (row == null) break;
                if (i == 0) System.out.println(row);
            }
            cursor.closeTopLevel();
            long end = System.nanoTime();
            if (i >= WARMUPS)
                time += (end - start) / 1.0e6;
        }
        System.out.println(String.format("%g ms", time / REPEATS));
    }

    @Test
    public void pojoAggregator() throws PersistitException {
        System.out.println("POJO");
        double time = 0.0;
        for (int i = 0; i < WARMUPS+REPEATS; i++) {
            long start = System.nanoTime();
            POJOAggregator aggregator = new POJOAggregator(i == 0);
            Exchange exchange = persistitStore().getExchange(session(), index);
            exchange.clear();
            exchange.append(Key.BEFORE);
            while (exchange.traverse(Key.GT, true)) {
                Key key = exchange.getKey();
                aggregator.aggregate(key);
            }
            aggregator.emit();
            persistitStore().releaseExchange(session(), exchange);
            long end = System.nanoTime();
            if (i >= WARMUPS)
                time += (end - start) / 1.0e6;
        }
        System.out.println(String.format("%g ms", time / REPEATS));
    }

    static class BespokeOperator extends Operator {
        private Operator inputOperator;
        private RowType outputType;

        public BespokeOperator(Operator inputOperator) {
            this.inputOperator = inputOperator;
            outputType = new BespokeRowType();
        }

        @Override
        protected Cursor cursor(QueryContext context, QueryBindingsCursor bindingsCursor) {
            return new BespokeCursor(context, API.cursor(inputOperator, context, bindingsCursor), outputType);
        }

        @Override
        public void findDerivedTypes(Set<RowType> derivedTypes) {
            inputOperator.findDerivedTypes(derivedTypes);
            derivedTypes.add(outputType);
        }

        @Override
        public List<Operator> getInputOperators() {
            return Collections.singletonList(inputOperator);
        }

        @Override
        public RowType rowType() {
            return outputType;
        }

        @Override
        public CompoundExplainer getExplainer(ExplainContext context) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    static class BespokeCursor extends OperatorCursor {
        private Cursor inputCursor;
        private RowType outputType;
        private ValuesHolderRow outputRow;
        private BespokeAggregator aggregator;

        public BespokeCursor(QueryContext context, Cursor inputCursor, RowType outputType) {
            super(context);
            this.inputCursor = inputCursor;
            this.outputType = outputType;
        }

        @Override
        public void open() {
            inputCursor.open();
            outputRow = new ValuesHolderRow(outputType);
            aggregator = new BespokeAggregator();
        }

        @Override
        public void close() {
            inputCursor.close();
            aggregator = null;
        }

        @Override
        public void destroy() {
            close();
            inputCursor = null;
        }

        @Override
        public boolean isIdle() {
            return ((inputCursor != null) && (aggregator == null));
        }

        @Override
        public boolean isActive() {
            return ((inputCursor != null) && (aggregator != null));
        }

        @Override
        public boolean isDestroyed() {
            return (inputCursor == null);
        }

        @Override
        public Row next() {
            if (aggregator == null)
                return null;
            while (true) {
                Row inputRow = inputCursor.next();
                if (inputRow == null) {
                    if (aggregator.isEmpty()) {
                        close();
                        return null;
                    }
                    aggregator.fill(outputRow);
                    close();
                    return outputRow;
                }
                if (aggregator.aggregate(inputRow, outputRow)) {
                    return outputRow;
                }
            }
        }

        @Override
        public void openBindings() {
            inputCursor.openBindings();
        }

        @Override
        public QueryBindings nextBindings() {
            return inputCursor.nextBindings();
        }

        @Override
        public void closeBindings() {
            inputCursor.closeBindings();
        }

        @Override
        public void cancelBindings(QueryBindings bindings) {
            inputCursor.cancelBindings(bindings);
        }
    }

    static final AkType[] TYPES = { 
        AkType.LONG, AkType.LONG, AkType.LONG, AkType.LONG
    };
    
    static final TInstance[] TINSTANCES = {
        MNumeric.INT.instance(true),MNumeric.INT.instance(true) ,MNumeric.INT.instance(true) ,MNumeric.INT.instance(true)
    };

    static class BespokeRowType extends RowType {
        public BespokeRowType() {
            super(-1);
        }

        @Override
        public DerivedTypesSchema schema() {
            return null;
        }

        public int nFields() {
            return TYPES.length;
        }

        @Override
        public AkType typeAt(int index) {
            return TYPES[index];
        }

        @Override
        public TInstance typeInstanceAt(int index) {
            return TINSTANCES[index];
        }
    }

    static class BespokeAggregator {
        private boolean key_init;
        private long key;
        private long count1;
        private boolean sum1_init;
        private long sum1;
        private boolean sum2_init;
        private long sum2;

        public boolean isEmpty() {
            return !key_init;
        }

        public boolean aggregate(Row inputRow, ValuesHolderRow outputRow) {
            // The select part.
            String sval = inputRow.eval(1).getString();
            if (("M".compareTo(sval) > 0) ||
                ("Y".compareTo(sval) < 0))
                return false;
            long flag = getLong(inputRow, 2);
            if (flag == 1)
                return false;

            // The actual aggregate part.
            boolean emit = false, reset = false;
            long nextKey = getLong(inputRow, 0);
            if (!key_init) {
                key_init = reset = true;
                key = nextKey;
            }
            else if (key != nextKey) {
                fill(outputRow);
                emit = reset = true;
                key = nextKey;
            }
            if (reset) {
                sum1_init = sum2_init = false;
                count1 = sum1 = sum2 = 0;
            }
            ValueSource value = inputRow.eval(3);
            if (!value.isNull()) {
                count1++;
            }
            value = inputRow.eval(4);
            if (!value.isNull()) {
                if (!sum1_init)
                    sum1_init = true;
                sum1 += value.getInt();
            }
            value = inputRow.eval(5);
            if (!value.isNull()) {
                if (!sum2_init)
                    sum2_init = true;
                sum2 += value.getInt();
            }
            return emit;
        }

        public void fill(ValuesHolderRow row) {
            row.holderAt(0).putLong(key);
            row.holderAt(1).putLong(count1);
            row.holderAt(2).putLong(sum1);
            row.holderAt(3).putLong(sum2);
        }

        @Override
        public String toString() {
            return String.format("%d: [%d %d %d]", key, count1, sum1, sum2);
        }

    }

    static class POJOAggregator {
        private boolean key_init;
        private long key;
        private long count1;
        private boolean sum1_init;
        private long sum1;
        private boolean sum2_init;
        private long sum2;

        private final boolean doPrint;

        public POJOAggregator(boolean doPrint) {
            this.doPrint = doPrint;
        }

        public void aggregate(Key row) {
            row.indexTo(1);
            String sval = row.decodeString();
            if (("M".compareTo(sval) > 0) ||
                ("Y".compareTo(sval) < 0))
                return;
            row.indexTo(2);
            long flag = row.decodeLong();
            if (flag == 1)
                return;
            row.indexTo(0);
            boolean reset = false;
            long nextKey = row.decodeLong();
            if (!key_init) {
                key_init = reset = true;
                key = nextKey;
            }
            else if (key != nextKey) {
                emit();
                reset = true;
                key = nextKey;
            }
            if (reset) {
                sum1_init = sum2_init = false;
                count1 = sum1 = sum2 = 0;
            }
            row.indexTo(3);
            if (!row.isNull()) {
                count1++;
            }
            row.indexTo(4);
            if (!row.isNull()) {
                if (!sum1_init)
                    sum1_init = true;
                sum1 += row.decodeLong();
            }
            row.indexTo(5);
            if (!row.isNull()) {
                if (!sum2_init)
                    sum2_init = true;
                sum2 += row.decodeLong();
            }
        }

        public void emit() {
            if (doPrint)
                System.out.println(this);
        }

        @Override
        public String toString() {
            return String.format("%d: [%d %d %d]", key, count1, sum1, sum2);
        }

    }

    @Test
    public void sorted() {
        Schema schema = new Schema(ais());
        IndexRowType indexType = schema.indexRowType(index);
        IndexKeyRange keyRange = IndexKeyRange.unbounded(indexType);
        API.Ordering ordering = new API.Ordering();
        ordering.append(field(indexType, 0), true);
        
        Operator plan = API.indexScan_Default(indexType, keyRange, ordering);
        RowType rowType = indexType;

        plan = spa(plan, rowType);
        rowType = plan.rowType();
        
        ordering = new API.Ordering();
        ordering.append(field(rowType, 2), true);
        plan = API.sort_InsertionLimited(plan, rowType, ordering, 
                                         API.SortOption.PRESERVE_DUPLICATES, 100);
        
        StoreAdapter adapter = newStoreAdapter(schema);
        QueryContext queryContext = queryContext(adapter);
        QueryBindings queryBindings = queryContext.createBindings();
        
        System.out.println("SORTED");
        double time = 0.0;
        for (int i = 0; i < WARMUPS+REPEATS; i++) {
            long start = System.nanoTime();
            Cursor cursor = API.cursor(plan, queryContext, queryBindings);
            cursor.openTopLevel();
            while (true) {
                Row row = cursor.next();
                if (row == null) break;
                if (i == 0) System.out.println(row);
            }
            cursor.closeTopLevel();
            long end = System.nanoTime();
            if (i >= WARMUPS)
                time += (end - start) / 1.0e6;
        }
        System.out.println(String.format("%g ms", time / REPEATS));
    }

    @Test
    public void parallel() {
        Schema schema = new Schema(ais());
        IndexRowType indexType = schema.indexRowType(index);
        ValuesRowType valuesType = schema.newValuesType(AkType.LONG, AkType.LONG);
        IndexBound lo = new IndexBound(new RowBasedUnboundExpressions(indexType, Collections.<ExpressionGenerator>singletonList(boundField(valuesType, 0, 0))), new SetColumnSelector(0));
        IndexBound hi = new IndexBound(new RowBasedUnboundExpressions(indexType, Collections.<ExpressionGenerator>singletonList(boundField(valuesType, 0, 1))), new SetColumnSelector(0));
        IndexKeyRange keyRange = IndexKeyRange.bounded(indexType, lo, true, hi, false);
        API.Ordering ordering = new API.Ordering();
        ordering.append(field(indexType, 0), true);
        
        Operator plan = API.indexScan_Default(indexType, keyRange, ordering);
        RowType rowType = indexType;

        plan = spa(plan, rowType);
        rowType = plan.rowType();

        int nthreads = Integer.parseInt(System.getProperty("test.nthreads", "4"));
        double n = (double)NKEYS / nthreads;
        List<ValuesRow> keyRows = new ArrayList<>();
        for (int i = 0; i < nthreads; i++) {
            Object[] values = new Object[2];
            values[0] = Math.round(n * i);
            values[1] = Math.round(n * (i+1));
            keyRows.add(new ValuesRow(valuesType, values));
        }
        plan = new Map_Parallel(plan, rowType, valuesType, keyRows, 0);
                                
        ordering = new API.Ordering();
        ordering.append(field(rowType, 2), true);
        plan = API.sort_InsertionLimited(plan, rowType, ordering, 
                                         API.SortOption.PRESERVE_DUPLICATES, 100);
        
        StoreAdapter adapter = newStoreAdapter(schema);
        QueryContext queryContext = queryContext(adapter);
        QueryBindings queryBindings = queryContext.createBindings();
        
        System.out.println("PARALLEL " + nthreads);
        double time = 0.0;
        for (int i = 0; i < WARMUPS+REPEATS; i++) {
            long start = System.nanoTime();
            Cursor cursor = API.cursor(plan, queryContext, queryBindings);
            cursor.openTopLevel();
            while (true) {
                Row row = cursor.next();
                if (row == null) break;
                if (i == 0) System.out.println(row);
            }
            cursor.closeTopLevel();
            long end = System.nanoTime();
            if (i >= WARMUPS)
                time += (end - start) / 1.0e6;
        }
        System.out.println(String.format("%g ms", time / REPEATS));
    }

    class Map_Parallel extends Operator {
        private Operator inputOperator;
        private RowType outputType;
        private ValuesRowType valuesType;
        private List<ValuesRow> valuesRows;
        private int bindingPosition;

        public Map_Parallel(Operator inputOperator, RowType outputType, ValuesRowType valuesType, List<ValuesRow> valuesRows, int bindingPosition) {
            this.inputOperator = inputOperator;
            this.outputType = outputType;
            this.valuesType = valuesType;
            this.valuesRows = valuesRows;
            this.bindingPosition = bindingPosition;
        }

        @Override
        protected Cursor cursor(QueryContext context, QueryBindingsCursor bindingsCursor) {
            return new ParallelCursor(context, bindingsCursor, inputOperator, valuesType, valuesRows, bindingPosition);
        }

        @Override
        public List<Operator> getInputOperators() {
            return Collections.singletonList(inputOperator);
        }

        @Override
        public CompoundExplainer getExplainer(ExplainContext context) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    // Nulls are not allowed in ConcurrentLinkedQueue.
    static final Object EOF_ROW = new Object();

    static class RowQueue {
        private final Thread reader;
        private Queue<Object> queue;
        
        public RowQueue(Thread reader) {
            this.reader = reader;
            queue = new ConcurrentLinkedQueue<>();
        }
        
        public Row take() throws InterruptedException {
            while (true) {
                Object row = queue.poll();
                if (row == null) {
                    // assert (reader == Thread.currentThread());
                    LockSupport.park(this);
                }
                else {
                    return ((row != EOF_ROW) ? (Row)row : null);
                }
            }
        }

        public void put(Row row) throws InterruptedException {
            boolean added = queue.offer((row != null) ? row : EOF_ROW);
            // No size limit on ConcurrentLinkedQueue. A real implementation would
            // need to block sometimes when too full.
            assert added;
            LockSupport.unpark(reader);
        }

        public void clear() {
            queue.clear();
        }
    }

    class ParallelCursor extends LeafCursor {
        private RowQueue queue;
        private List<WorkerThread> threads;
        private int nrunning;
        private Row heldRow;

        public ParallelCursor(QueryContext context, QueryBindingsCursor bindingsCursor, Operator inputOperator, ValuesRowType valuesType, List<ValuesRow> valuesRows, int bindingPosition) {
            super(context, bindingsCursor);
            int nthreads = valuesRows.size();
            queue = new RowQueue(Thread.currentThread());
            threads = new ArrayList<>(nthreads);
            for (ValuesRow valuesRow : valuesRows) {
                threads.add(new WorkerThread(inputOperator, valuesType, valuesRow, bindingPosition, queue));
            }
        }

        @Override
        public void open() {
            nrunning = 0;
            for (WorkerThread thread : threads) {
                thread.open();
                nrunning++;
            }
        }

        @Override
        public void close() {
            if (heldRow != null) {
                heldRow.release();
                heldRow = null;
            }
            for (WorkerThread thread : threads) {
                if (thread.close())
                    nrunning--;
            }
            // TODO: Could be off if closed prematurely and there are
            // nulls in the queue (or waiting to be added).
            assert (nrunning == 0) : nrunning;
        }

        @Override
        public void destroy() {
            for (WorkerThread thread : threads) {
                thread.destroy();
            }
            threads = null;
            queue.clear();
            queue = null;
        }

        @Override
        public boolean isIdle() {
            return (nrunning == 0);
        }

        @Override
        public boolean isActive() {
            return (nrunning > 0);
        }

        @Override
        public boolean isDestroyed() {
            return (threads == null);
        }

        @Override
        public Row next() {
            while (nrunning > 0) {
                if (heldRow != null) {
                    heldRow.release();
                    heldRow = null;
                }
                try {
                    Row row = queue.take();
                    if (row != null)
                        heldRow = row; // Was acquired by worker.
                }
                catch (InterruptedException ex) {
                    throw new QueryCanceledException(context.getSession());
                }
                if (heldRow == null)
                    nrunning--;
                else
                    return heldRow;
            }
            return null;
        }
    }

    class WorkerThread implements Runnable {
        private Session session;
        private StoreAdapter adapter;
        private QueryContext context;
        private QueryBindings bindings;
        private Cursor inputCursor;        
        private RowQueue queue;
        private Thread thread;
        private volatile boolean open;
        
        public WorkerThread(Operator inputOperator, ValuesRowType valuesType, ValuesRow valuesRow, int bindingPosition, RowQueue queue) {
            session = createNewSession();
            adapter = newStoreAdapter(session, (Schema)valuesType.schema());
            context = queryContext(adapter);
            bindings = context.createBindings();
            bindings.setRow(bindingPosition, valuesRow);
            inputCursor = API.cursor(inputOperator, context, bindings);
            this.queue = queue;
        }
        
        public void open() {
            thread = new Thread(this);
            thread.start();
        }

        public boolean close() {
            if (!open) return false;
            thread.interrupt();
            //thread.join();
            return true;
        }

        public void destroy() {
            close();
            if (inputCursor != null) {
                inputCursor.destroy();
                inputCursor = null;
            }
            session.close();
        }

        @Override
        public void run() {
            inputCursor.openTopLevel();
            open = true;
            txnService().beginTransaction(session);
            try {
                while (open) {
                    Row row = inputCursor.next();
                    if (row == null) 
                        open = false;
                    else
                        row.acquire();
                    queue.put(row);
                }
                txnService().commitTransaction(session);
            }
            catch (InterruptedException ex) {
                throw new QueryCanceledException(context.getSession());
            }
            finally {
                inputCursor.closeTopLevel();
                txnService().commitTransaction(session);
            }
        }

    }

}