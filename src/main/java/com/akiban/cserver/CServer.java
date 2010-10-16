package com.akiban.cserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.akiban.ais.model.Group;
import com.akiban.ais.model.TableName;
import com.akiban.cserver.manage.SchemaManager;
import com.akiban.message.*;
import com.akiban.util.MySqlStatementSplitter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.akiban.admin.Admin;
import com.akiban.ais.ddl.DDLSource;
import com.akiban.ais.io.Writer;
import com.akiban.ais.message.AISExecutionContext;
import com.akiban.ais.message.AISRequest;
import com.akiban.ais.message.AISResponse;
import com.akiban.ais.model.AkibaInformationSchema;
import com.akiban.ais.model.GroupTable;
import com.akiban.ais.model.UserTable;
import com.akiban.ais.util.AISPrinter;
import com.akiban.cserver.manage.MXBeanManager;
import com.akiban.cserver.message.ShutdownRequest;
import com.akiban.cserver.message.ShutdownResponse;
import com.akiban.cserver.message.ToStringWithRowDefCache;
import com.akiban.cserver.store.PersistitStore;
import com.akiban.cserver.store.Store;
import com.akiban.util.Tap;

/**
 * @author peter
 */
public class CServer implements CServerConstants {

    private static final Log LOG = LogFactory.getLog(CServer.class.getName());

    private static final String AIS_DDL_NAME = "akiba_information_schema.ddl";

    private static final int GROUP_TABLE_ID_OFFSET = 1000000000;
    /**
     * Config property name and default for the port on which the CServer will
     * listen for requests.

    /**
     * Port on which the CServer will listen for requests.
     */
    public static final int CSERVER_PORT =
            Integer.parseInt(System.getProperty("cserver.port", DEFAULT_CSERVER_PORT_STRING));

    /**
     * Interface on which this cserver instance will listen. TODO - allow
     * multiple NICs
     */
    // TODO: Why would it ever be anything other than localhost?
    // PDB: because the machine may have more than one NIC and localhost is
    // bound to only one of them.
    public static final String CSERVER_HOST = 
            System.getProperty("cserver.host", DEFAULT_CSERVER_HOST_STRING);

    /**
     * Config property name and default for setting of the verbose flag. When
     * true, many CServer methods log verbosely at INFO level.
     */

    private static final String VERBOSE_PROPERTY_NAME = "cserver.verbose";

    private static final boolean USE_NETTY = Boolean.parseBoolean(System.getProperty("usenetty", "false"));

    /**
     * Name of this chunkserver. Must match one of the entries in
     * /config/cluster.properties (managed by Admin).
     */
    private static final String CSERVER_NAME = System
            .getProperty("cserver.name");

    private static Tap CSERVER_EXEC = Tap.add(new Tap.PerThread("cserver",
            Tap.TimeStampLog.class));

    private final static List<String> aisSchemaDdls = readAisDdls();
    private final static List<CreateTableStruct> aisSchemaStructs = readAisSchemaDdls(aisSchemaDdls);
    private final RowDefCache rowDefCache;
    private final CServerConfig config;
    private final PersistitStore store;
    private final int cserverPort;
    private final ExecutionContext executionContext = new CServerContext();
    private AbstractCServerRequestHandler requestHandler;
    private AkibaInformationSchema ais;
    private volatile boolean stopped;
    private boolean leadCServer;
    private AISDistributor aisDistributor;

    private static final int AIS_BASE_TABLE_IDS = 10000;

    private volatile Thread _shutdownHook;

    /**
     * Construct a chunk server. If <tt>loadConfig</tt> is false then use
     * default unit test properties.
     * 
     * @param loadConfig
     * @throws Exception
     */
    public CServer(final boolean loadConfig) throws Exception {
        cserverPort = CSERVER_PORT;
        rowDefCache = new RowDefCache();
        if (loadConfig) {
            config = new CServerConfig();
            if (loadConfig) {
                config.load();
                if (config.getException() != null) {
                    LOG.fatal("CServer configuration failed");
                    throw new Exception("CServer configuration failed");
                }
            }
        } else {
            config = CServerConfig.unitTestConfig();
        }

        store = new PersistitStore(config, rowDefCache);
    }

    public void start() throws Exception {
        start(true);
    }

    public void start(boolean startNetwork) throws Exception {
        LOG.warn(String.format("Starting chunkserver %s on port %s",
                CSERVER_NAME, CSERVER_PORT));
        Tap.registerMXBean();
        MXBeanManager.registerMXBean(this, config);
        if (startNetwork) {
            MessageRegistry.initialize();
            MessageRegistry.only().registerModule("com.akiban.cserver");
            MessageRegistry.only().registerModule("com.akiban.ais");
            MessageRegistry.only().registerModule("com.akiban.message");
        }
        store.startUp();
        store.setVerbose(config.property(VERBOSE_PROPERTY_NAME, "false")
                .equalsIgnoreCase("true"));
        store.setOrdinals();
        acquireAIS();
        if (false) {
            Admin admin = Admin.only();

            leadCServer = admin.clusterConfig().leadChunkserver().name()
                    .equals(CSERVER_NAME);
            admin.markChunkserverUp(CSERVER_NAME);
            if (isLeader()) {
                aisDistributor = new AISDistributor(this);
            }
        } else {
            leadCServer = true;
        }
        LOG.warn(String.format("Started chunkserver %s on port %s, lead = %s",
                CSERVER_NAME, CSERVER_PORT, isLeader()));
        _shutdownHook = new Thread(new Runnable() {
            public void run() {
                try {
                    stop();
                } catch (Exception e) {

                }
            }
        }, "ShutdownHook");

        Runtime.getRuntime().addShutdownHook(_shutdownHook);

        if (startNetwork) {
            requestHandler =
                    USE_NETTY
                    ? CServerRequestHandler_Netty.start(this, CSERVER_HOST, CSERVER_PORT)
                    : CServerRequestHandler.start(this, CSERVER_HOST, CSERVER_PORT);
        }
    }

    public void stop() throws Exception {
        stopped = true;
        final Thread hook = _shutdownHook;
        _shutdownHook = null;
        if (hook != null) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
        if (requestHandler != null) {
            requestHandler.stop();
        } // else: testing - chunkserver started without network
        if (false) {
            // TODO: Use this when we support multiple chunkservers
            Admin.only().markChunkserverDown(CSERVER_NAME);
        }
        MXBeanManager.unregisterMXBean();
        Tap.unregisterMXBean();
        store.shutDown();
    }

    public String host() {
        return CSERVER_HOST;
    }

    public int port() {
        return cserverPort;
    }

    public PersistitStore getStore() {
        return store;
    }

    public class CServerContext implements ExecutionContext,
            AISExecutionContext, CServerShutdownExecutionContext {

        public Store getStore() {
            return store;
        }

        public SchemaManager getSchemaManager() {
            return MXBeanManager.getSchemaManager();
        }

        @Override
        public void executeRequest(AkibaSendConnection connection,
                AISRequest request) throws Exception {
            AISResponse aisResponse = new AISResponse(ais);
            connection.send(aisResponse);
        }

        @Override
        public void executeResponse(AkibaSendConnection connection,
                AISResponse response) throws Exception {
            CServer.this.ais = response.ais();
            CServer.this.installAIS();
        }

        @Override
        public void executeRequest(AkibanConnection connection,
                ShutdownRequest request) throws Exception {
            if (LOG.isInfoEnabled()) {
                LOG.info("CServer stopping due to ShutdownRequest");
            }
            ShutdownResponse response = new ShutdownResponse();
            connection.send(response);
            stop();
        }

        public void installAIS(AkibaInformationSchema ais) throws Exception {
            LOG.info("Installing AIS");
            CServerAisTarget target = new CServerAisTarget(store);
            new Writer(target).save(ais);
            CServer.this.ais = ais;
            CServer.this.installAIS();
            LOG.info("AIS installation complete");
        }
    }

    Runnable newRunnable(AkibanConnection connection)
    {
        return new CServerRunnable(connection);
    }

    ExecutionContext executionContext()
    {
        return executionContext;
    }

    Message executeMessage(Message request) {
        if (stopped) {
            return new ErrorResponse(ErrorCode.SERVER_SHUTDOWN, "Server is shutting down");
        }

        final SingleSendBuffer sendBuffer = new SingleSendBuffer();
        try {
            request.execute(sendBuffer, executionContext);
        }
        catch (InvalidOperationException e) {
            sendBuffer.send(new ErrorResponse(e.getCode(), e.getMessage()));
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Message type %s generated an error", request.getClass()), e);
            }
        }
        catch (Throwable t) {
            sendBuffer.send(new ErrorResponse(t));
        }
        return sendBuffer.getMessage();
    }

    void executeRequest(ExecutionContext executionContext, AkibanConnection connection, Request request) throws Exception
    {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Serving message " + request);
        }
        CSERVER_EXEC.in();
        if (store.isVerbose() && LOG.isInfoEnabled()) {
            LOG.info(String.format("Executing %s",
                                   request instanceof ToStringWithRowDefCache
                                   ? ((ToStringWithRowDefCache) request).toString(rowDefCache)
                                   : request.toString()));
        }
        Message response = executeMessage(request);
        connection.send(response);
        CSERVER_EXEC.out();
    }
    
    /**
     * A Runnable that reads Network messages, acts on them and returns results.
     * 
     * @author peter
     * 
     */
    private class CServerRunnable implements Runnable {

        private final AkibanConnection connection;

        public CServerRunnable(final AkibanConnection connection) {
            this.connection = connection;
        }

        public void run() {
            Message message = null;
            while (!stopped) {
                try {
                    message = connection.receive();
                    executeRequest(executionContext, connection, (Request) message);
                } catch (InterruptedException e) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Thread " + Thread.currentThread().getName()
                                + (stopped ? " stopped" : " interrupted"));
                    }
                    break;
                }
                catch (Exception e) {
                    if (LOG.isErrorEnabled()) {
                        LOG.error("Unexpected error on " + message, e);
                    }
                    if (message != null) {
                        try {
                            connection.send(new ErrorResponse(e));
                        } catch (Exception f) {
                            LOG.error("Caught " + f.getClass()
                                    + " while sending error response to "
                                    + message + ": " + f.getMessage(), f);
                        }
                    }
                }
            }
        }
    }

    public static class CreateTableStruct {
        final int tableId;
        final String schemaName;
        final String tableName;
        final String ddl;

        public CreateTableStruct(int tableId, String schemaName,
                String tableName, String ddl) {
            this.tableId = tableId;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.ddl = ddl;
        }

        public String getDdl() {
            return ddl;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public String getTableName() {
            return tableName;
        }

        public int getTableId() {
            return tableId;
        }

        @Override
        public String toString() {
            return "CreateTableStruct[" + tableId + ": "
                    + TableName.create(schemaName, tableName) + ']';
        }
    }

    public RowDefCache getRowDefCache() {
        return rowDefCache;
    }

    /**
     * Intended for testing. Creates a new AIS with only the a_i_s tables as its
     * user tables.
     * 
     * @return a new AIS instance
     * @throws Exception
     *             if there's a problem
     */
    public AkibaInformationSchema createEmptyAIS() throws Exception {
        return createFreshAIS(new ArrayList<CreateTableStruct>());
    }

    /**
     * Creates a fresh AIS, using as its source the default AIS schema DDLs as
     * well as any stored schemata.
     * 
     * @param schema
     *            the stored schema to add to the default AIS schema.
     * @return a new AIS
     * @throws Exception
     *             if there's a problem
     */
    private AkibaInformationSchema createFreshAIS(
            final List<CreateTableStruct> schema) throws Exception {
        schema.addAll(0, aisSchemaStructs);

        assert !schema.isEmpty() : "schema list is empty";
        final StringBuilder sb = new StringBuilder();
        for (final CreateTableStruct tableStruct : schema) {
            sb.append("CREATE TABLE ").append(tableStruct.ddl)
                    .append(CServerUtil.NEW_LINE);
        }
        final String schemaText = sb.toString();
        if (getStore().isVerbose() && LOG.isInfoEnabled()) {
            LOG.info("Acquiring AIS from schema: " + CServerUtil.NEW_LINE
                    + schemaText);
        }

        AkibaInformationSchema ret = new DDLSource()
                .buildAISFromString(schemaText);
        for (final CreateTableStruct tableStruct : schema) {
            final UserTable table = ret.getUserTable(tableStruct.schemaName,
                    tableStruct.tableName);
            assert table != null : tableStruct + " in " + schema;
            assert table.getGroup() != null : "table "
                    + table
                    + " has no group; should be in a single-table group at least";
            table.setTableId(tableStruct.tableId);
            if (table.getParentJoin() == null) {
                final GroupTable groupTable = table.getGroup().getGroupTable();
                if (groupTable != null) {
                    groupTable.setTableId(tableStruct.tableId
                            + GROUP_TABLE_ID_OFFSET);
                }
            }
        }
        return ret;
    }

    /**
     * Acquire an AkibaInformationSchema from MySQL and install it into the
     * local RowDefCache.
     * 
     * This method always refreshes the locally cached AkibaInformationSchema to
     * support schema modifications at the MySQL head.
     * 
     * @return an AkibaInformationSchema
     * @throws Exception
     */
    public synchronized void acquireAIS() throws Exception {
        if (!store.isExperimentalSchema()) {
            throw new UnsupportedOperationException(
                    "non-experimental mode is deprecated.");
        }

        this.ais = createFreshAIS(store.getSchema());
        installAIS();
        new Writer(new CServerAisTarget(store)).save(ais);
    }

    boolean isLeader() {
        return leadCServer;
    }

    private synchronized void installAIS() throws Exception {
        if (LOG.isInfoEnabled()) {
            LOG.info("Installing " + ais.getDescription() + " in ChunkServer");
            LOG.debug(AISPrinter.toString(ais));
        }
        rowDefCache.clear();
        rowDefCache.setAIS(ais);
        store.setOrdinals();
        if (false) {
            // TODO: Use this when we support multiple chunkservers
            if (isLeader()) {
                assert aisDistributor != null;
                aisDistributor.distribute(ais);
            }
        }
    }

    private static List<CreateTableStruct> readAisSchemaDdls(List<String> ddls) {
        final Pattern regex = Pattern.compile("create table (\\w+)",
                Pattern.CASE_INSENSITIVE);
        List<CreateTableStruct> tmp = new ArrayList<CreateTableStruct>();
        int tableId = AIS_BASE_TABLE_IDS;
        for (String ddl : ddls) {
            Matcher matcher = regex.matcher(ddl);
            if (!matcher.find()) {
                throw new RuntimeException("couldn't match regex for: " + ddl);
            }
            String hackedDdl = "`akiba_information_schema`." + matcher.group(1)
                    + ddl.substring(matcher.end());
            CreateTableStruct struct = new CreateTableStruct(tableId++,
                    "akiba_information_schema", matcher.group(1), hackedDdl);
            tmp.add(struct);
        }
        return Collections.unmodifiableList(tmp);
    }

    private static List<String> readAisDdls() {
        final List<String> ret = new ArrayList<String>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(
                CServer.class.getClassLoader()
                        .getResourceAsStream(AIS_DDL_NAME)));
        for (String ddl : (new MySqlStatementSplitter(reader))) {
            ret.add(ddl);
        }
        return Collections.unmodifiableList(ret);
    }

    /**
     * Returns an unmodifiable list of AIS DDLs.
     * 
     * @return
     */
    public static List<String> getAisDdls() {
        return aisSchemaDdls;
    }

    /**
     * Do not invoke this. It should only be invoked by a SchemaManager class.
     * Any other usage will throw an assertion if -enableassertions is on.
     * 
     * @return a copy of the currently installed AIS, minus all a_i_s.* tables
     *         and their associated group tables.
     */
    public AkibaInformationSchema getAisCopy() {
        assert getAisCopyCallerIsOkay();
        AkibaInformationSchema ret = new AkibaInformationSchema(ais);
        List<TableName> uTablesToRemove = new ArrayList<TableName>();
        for (Map.Entry<TableName, UserTable> entries : ret.getUserTables()
                .entrySet()) {
            TableName tableName = entries.getKey();
            if (tableName.getSchemaName().equals("akiba_information_schema")
                    || tableName.getSchemaName().equals("akiba_objects")) {
                uTablesToRemove.add(tableName);
                UserTable uTable = entries.getValue();
                Group group = uTable.getGroup();
                if (group != null) {
                    ret.getGroups().remove(group.getName());
                    ret.getGroupTables()
                            .remove(group.getGroupTable().getName());
                }
            }
        }
        for (TableName removeMe : uTablesToRemove) {
            ret.getUserTables().remove(removeMe);
        }
        return ret;
    }

    /**
     * Asserts that the caller of getAisCopy()
     * 
     * @return <tt>true</tt>, always; if there's an error, this method will
     *         raise the assertion.
     */
    private static boolean getAisCopyCallerIsOkay() {
        StackTraceElement callerStack = Thread.currentThread().getStackTrace()[3];
        final Class<?> callerClass;
        try {
            callerClass = Class.forName(callerStack.getClassName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("couldn't load class "
                    + callerStack.getClassName(), e);
        }
        assert SchemaManager.class.isAssignableFrom(callerClass) : "invalid calling class "
                + callerClass;
        return true;
    }

    /**
     * @param args
     *            the command line arguments
     */
    public static void main(String[] args) throws Exception {
        try {
            final CServer server = new CServer(true);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // HAZEL: MySQL Conference Demo 4/2010: MySQL/Drizzle/Memcache to Chunk
        // Server
        /*
         * com.thimbleware.jmemcached.protocol.MemcachedCommandHandler.
         * registerCallback ( new
         * com.thimbleware.jmemcached.protocol.MemcachedCommandHandler.Callback
         * () { public byte[] get(byte[] key) { byte[] result = null;
         * 
         * String request = new String(key); String[] tokens =
         * request.split(":"); if (tokens.length == 4) { String schema =
         * tokens[0]; String table = tokens[1]; String colkey = tokens[2];
         * String colval = tokens[3];
         * 
         * try { List<RowData> list = null; //list =
         * server.store.fetchRows(schema, table, colkey, colval, colval,
         * "order"); list = server.store.fetchRows(schema, table, colkey,
         * colval, colval, null);
         * 
         * StringBuilder builder = new StringBuilder(); for (RowData data: list)
         * { builder.append(data.toString(server.getRowDefCache()) + "\n");
         * //builder.append(data.toString()); }
         * 
         * result = builder.toString().getBytes(); } catch (Exception e) {
         * result = new String("read error: " + e.getMessage()).getBytes(); } }
         * else { result = new String("invalid key: " + request).getBytes(); }
         * 
         * return result; } }); com.thimbleware.jmemcached.Main.main(new
         * String[0]);
         */
    }

    public String property(final String key, final String dflt) {
        return config.property(key, dflt);
    }

    /**
     * For unit tests
     * 
     * @param key
     * @param value
     */
    public void setProperty(final String key, final String value) {
        config.setProperty(key, value);
    }

    public static class CapturedMessage {
        final long eventTime;
        long gap;
        long elapsed;
        final Message message;
        final String threadName;

        private CapturedMessage(final Message message) {
            this.eventTime = System.currentTimeMillis();
            this.message = message;
            this.threadName = Thread.currentThread().getName();
        }

        private void finish(final long elapsed, final long gap) {
            this.elapsed = elapsed;
            this.gap = gap;
        }

        public long getEventTime() {
            return eventTime;
        }

        public long getElapsedTime() {
            return elapsed;
        }

        public Message getMessage() {
            return message;
        }

        public String getThreadName() {
            return threadName;
        }

        private final static SimpleDateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS");
        private final static String TO_STRING_FORMAT = "%12s  %s  elapsed=%,12dus  gap=%,12dus  %s";

        @Override
        public String toString() {
            return toString(null);
        }

        public String toString(final RowDefCache rowDefCache) {
            return String
                    .format(TO_STRING_FORMAT,
                            threadName,
                            SDF.format(new Date(eventTime)),
                            elapsed,
                            gap,
                            message instanceof ToStringWithRowDefCache ? ((ToStringWithRowDefCache) message)
                                    .toString(rowDefCache) : message.toString());
        }
    }
}
