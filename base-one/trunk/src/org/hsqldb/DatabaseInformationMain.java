/* Copyright (c) 2001-2002, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG, 
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, 
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Enumeration;
import org.hsqldb.lib.HsqlArrayList;
import org.hsqldb.lib.HsqlHashSet;
import org.hsqldb.lib.HsqlHashMap;
import org.hsqldb.lib.StopWatch;
import org.hsqldb.lib.ValuePool;
import org.hsqldb.lib.enum.ArrayEnumeration;
import org.hsqldb.lib.enum.CompositeEnumeration;
import org.hsqldb.lib.enum.EmptyEnumeration;

// fredt@users - 1.7.2 - structural modifications to allow inheritance
// boucherb@users - 1.7.2 - 20020225
// - factored out all reusable code into DIXXX support classes
// - completed Fred's work on allowing inheritance
// boucherb@users - 1.7.2 - 20020304 - bug fixes, refinements, better java docs

/**
 * Produces tables which form a view of the system data dictionary. <p>
 *
 * Implementations use a group of arrays of equal size to store various
 * attributes or cached instances of system tables.<p>
 *
 * Two fixed static lists of reserved table names are kept in String[] and
 * HsqlName[] forms. These are shared by all implementations of
 * DatabaseInformtion.<p>
 *
 * Each implementation keeps a lookup set of names for those tables whose
 * contents are never cached (nonCachedTablesSet). <p>
 *
 * An instance of this class uses three lists named sysTablexxxx for caching
 * system tables.<p>
 *
 * sysTableSessionDependent indicates which tables contain data that is
 * dependent on the user rights of the User associatiod with the Session.<p>
 *
 * sysTableSessions contains the Session with whose rights each cached table
 * was built.<p>
 *
 * sysTables contains the cached tables.<p>
 *
 * At the time of instantiation, which is part of the Database.open() method
 * invocation, an empty table is created and placed in sysTables with calls to
 * generateTable(int) for each name in sysTableNames. Some of these
 * table entries may be null if an implementation does not produce them.<p>
 *
 * Calls to getSystemTable(String, Session) return a cached table if various
 * caching rules are met (see below), or it will delete all rows of the table
 * and rebuild the contents via generateTable(int).<p>
 *
 * generateTable(int) calls the appropriate single method for each table.
 * These methods either build and return an empty table (if sysTables
 * contains null for the table slot) or populate the table with up-to-date
 * rows. <p>
 *
 * When the setDirty() call is made externally, the internal isDirty flag
 * is set. This flag is used next time a call to
 * getSystemTable(String, Session) is made. <p>
 *
 * Rules for caching are applied as follows:
 *
 * When a call to getSystemTable(String, Session) is made, if the isDirty flag
 * is true, then the contents of all cached tables are cleared and the
 * sysTableUsers slot for all tables is set to null.
 *
 * If a table has non-cached contents, its contents are cleared and rebuilt.
 *
 * For the rest of the tables, if the sysTableSessions slot is null or if the
 * Session parameter is not the same as the Session object
 * in that slot, the table contents are cleared and rebuilt.
 *
 * (fredt@users)
 *
 * @author Campbell Boucher-Burnet, Camco & Associates Consulting
 * @version 1.7.2
 * @since HSQLDB 1.7.1
 */
class DatabaseInformationMain extends DatabaseInformation implements DITypes {

    // HsqlName objects for the system tables

    /** The HsqlNames of the system tables. */
    protected static final HsqlName[] sysTableHsqlNames;

    static {
        sysTableHsqlNames = new HsqlName[sysTableNames.length];

        for (int i = 0; i < sysTableNames.length; i++) {
            sysTableHsqlNames[i] = HsqlName.newAutoName(null,
                    sysTableNames[i]);
        }
    }

    /** current user for each cached system table */
    protected Session[] sysTableSessions = new Session[sysTableNames.length];

    /** true if the contents of a cached system table depends on the session */
    protected boolean[] sysTableSessionDependent =
        new boolean[sysTableNames.length];

    /** cache of system tables */
    protected Table[] sysTables = new Table[sysTableNames.length];

    /** Set: { names of system tables that are not to be cached } */
    protected static HsqlHashSet nonCachedTablesSet;

    /**
     * Map: simple <code>Column</code> name <code>String</code> object =>
     * <code>HsqlName</code> object.
     */
    protected static HsqlHashMap columnNameMap;

    /**
     * Map: simple <code>Index</code> name <code>String</code> object =>
     * <code>HsqlName</code> object.
     */
    protected static HsqlHashMap indexNameMap;

    /**
     * The <code>Session</code> object under consideration in the current
     * executution context.
     */
    protected Session session;

    /** The table types HSQLDB supports. */
    protected static final String[] tableTypes = new String[] {
        "GLOBAL TEMPORARY", "SYSTEM TABLE", "TABLE", "VIEW"
    };

    /** Provides naming support. */
    protected DINameSpace ns;

    /**
     * Constructs a table producer which provides system tables
     * for the specified <code>Database</code> object. <p>
     *
     * <b>Note:</b> it is important to observe that by specifying an instance
     * of this class to handle system table production, the default permissions
     * (and possibly aliases) of the indicated database are upgraded, meaning
     * that metadata reporting may be rendered insecure if the same database
     * is opened again if using a less capable system table producer instance.
     * If it is possible that this situation might arise, then care must be taken
     * to resove these issues, possibly by manual modification of the database's
     * REDO log (script file). <p>
     *
     * For now: BE WARNED. <p>
     *
     * In a future release, it may be that system-generated permissions
     * and aliases are not recorded in the REDO log, removing the associated
     * <em>dangers</em>. This may well be possible to implement with little or
     * no side-effects, since these permissions and aliases must always be
     * present for proper core operation.  That is, they can and probably
     * should be programatically reintroduced on each startup and protected
     * from modification for the life of the database instance, separate from
     * permissions and aliases introduced externally via user SQL. <p>
     * @param db the <code>Database</code> object for which this object produces
     *      system tables
     * @throws SQLException if a database access error occurs
     */
    DatabaseInformationMain(Database db) throws SQLException {

        super(db);

        Trace.doAssert(db != null, "database is null");
        Trace.doAssert(db.getTables() != null, "database table list is null");
        Trace.doAssert(db.getUserManager() != null, "user manager is null");
        init();
    }

    /**
     * Adds a <code>Column</code> object with the specified name, data type
     * and nullability to the specified <code>Table</code> object.
     * @param t the table to which to add the specified column
     * @param name the name of the column
     * @param type the data type of the column
     * @param nullable <code>true</code> if the column is to allow null values,
     *    else <code>false</code>
     * @throws SQLException if a problem occurs when adding the
     *      column (e.g. duplicate name)
     */
    protected void addColumn(Table t, String name, int type,
                             boolean nullable) throws SQLException {

        HsqlName cn;
        Column   c;

        cn = ns.findOrCreateHsqlName(name, columnNameMap);
        c  = new Column(cn, nullable, type, 0, 0, false, false, null);

        t.addColumn(c);
    }

    /**
     * Adds a nullable <code>Column</code> object with the specified name and
     * data type to the specified <code>Table</code> object.
     * @param t the table to which to add the specified column
     * @param name the name of the column
     * @param type the data type of the column
     * @throws SQLException if a problem occurs when adding the
     *      column (e.g. duplicate name)
     */
    protected void addColumn(Table t, String name,
                             int type) throws SQLException {
        addColumn(t, name, type, true);
    }

    /**
     * Adds to the specified <code>Table</code> object a non-primary
     * <code>Index</code> object on the specified columns, having the specified
     * uniqueness property.
     * @param t the table to which to add the specified index
     * @param indexName the simple name of the index
     * @param cols zero-based array of column numbers specifying the columns
     *    to include in the index
     * @param unique <code>true</code> if a unique index is desired,
     *    else <code>false</code>
     * @throws SQLException if there is a problem adding the specified index to the specified table
     */
    protected void addIndex(Table t, String indexName, int[] cols,
                            boolean unique) throws SQLException {

        HsqlName name;

        if (indexName == null || cols == null) {

            // do nothing
        } else {
            name = ns.findOrCreateHsqlName(indexName, indexNameMap);

            t.createIndex(cols, name, unique);
        }
    }

    protected Enumeration allTables() {

        return new CompositeEnumeration(database.getTables().elements(),
                                        new ArrayEnumeration(sysTables,
                                            true));
    }

    /** Clears the contents of cached system tables and resets users to null */
    protected void cacheClear() throws SQLException {

        int i = sysTables.length;

        while (--i > 0) {
            Table t = sysTables[i];

            if (t != null) {
                t.clearAllRows();
            }

            sysTableSessions[i] = null;
        }

        isDirty = false;
    }

    /**
     * Retrieves the system table corresponding to the specified index.
     * @param tableIndex index identifying the system table to generate
     * @throws SQLException if a database access error occurs
     * @return the system table corresponding to the specified index
     */
    protected Table generateTable(int tableIndex) throws SQLException {

        Table t = sysTables[tableIndex];

        switch (tableIndex) {

            case SYSTEM_BESTROWIDENTIFIER :
                return SYSTEM_BESTROWIDENTIFIER();

            case SYSTEM_CATALOGS :
                return SYSTEM_CATALOGS();

            case SYSTEM_COLUMNPRIVILEGES :
                return SYSTEM_COLUMNPRIVILEGES();

            case SYSTEM_COLUMNS :
                return SYSTEM_COLUMNS();

            case SYSTEM_CROSSREFERENCE :
                return SYSTEM_CROSSREFERENCE();

            case SYSTEM_INDEXINFO :
                return SYSTEM_INDEXINFO();

            case SYSTEM_PRIMARYKEYS :
                return SYSTEM_PRIMARYKEYS();

            case SYSTEM_PROCEDURECOLUMNS :
                return SYSTEM_PROCEDURECOLUMNS();

            case SYSTEM_PROCEDURES :
                return SYSTEM_PROCEDURES();

            case SYSTEM_SCHEMAS :
                return SYSTEM_SCHEMAS();

            case SYSTEM_TABLEPRIVILEGES :
                return SYSTEM_TABLEPRIVILEGES();

            case SYSTEM_TABLES :
                return SYSTEM_TABLES();

            case SYSTEM_TABLETYPES :
                return SYSTEM_TABLETYPES();

            case SYSTEM_TYPEINFO :
                return SYSTEM_TYPEINFO();

            case SYSTEM_USERS :
                return SYSTEM_USERS();

            case SYSTEM_ALLTYPEINFO :
                return SYSTEM_ALLTYPEINFO();

            default :
                return null;
        }
    }

    /**
     * One time initialisation of instance at construction time.
     * @throws SQLException if a database access error occurs
     */
    protected void init() throws SQLException {

        StopWatch sw = null;

        if (Trace.TRACE) {
            sw = new StopWatch();
        }

        ns                 = new DINameSpace(database);
        columnNameMap      = new HsqlHashMap();
        indexNameMap       = new HsqlHashMap();
        nonCachedTablesSet = new HsqlHashSet();

        // build the set of non-cached tables
        nonCachedTablesSet.add("SYSTEM_CACHEINFO");
        nonCachedTablesSet.add("SYSTEM_CONNECTIONINFO");
        nonCachedTablesSet.add("SYSTEM_SESSIONS");
        nonCachedTablesSet.add("SYSTEM_PROPERTIES");

        // flag the Session-dependent cached tables
        sysTableSessionDependent[SYSTEM_COLUMNPRIVILEGES] =
            sysTableSessionDependent[SYSTEM_CROSSREFERENCE] =
            sysTableSessionDependent[SYSTEM_INDEXINFO] =
            sysTableSessionDependent[SYSTEM_PRIMARYKEYS] =
            sysTableSessionDependent[SYSTEM_TABLES] =
            sysTableSessionDependent[SYSTEM_TRIGGERCOLUMNS] =
            sysTableSessionDependent[SYSTEM_TRIGGERS] =
            sysTableSessionDependent[SYSTEM_VIEWS] = true;

        Table   t;
        Session oldSession = session;

        session = database.getSysSession();

        Trace.check(session != null, Trace.USER_NOT_FOUND,
                    UserManager.SYS_USER_NAME);

        for (int i = 0; i < sysTables.length; i++) {
            t = sysTables[i] = generateTable(i);

            if (t != null) {
                t.setDataReadOnly(true);
            }
        }

        UserManager um = database.getUserManager();

        for (int i = 0; i < sysTableHsqlNames.length; i++) {
            if (sysTables[i] != null) {
                um.grant("PUBLIC", sysTableHsqlNames[i], UserManager.SELECT);
            }
        }

        session = oldSession;

        if (Trace.TRACE) {
            Trace.trace(this + ".initProduces() in " + sw.elapsedTime()
                        + " ms.");
        }
    }

    /**
     * Retrieves whether any form of SQL access is allowed against the
     * the specified table w.r.t the database access rights
     * assigned to current Session object's User. <p>
     * @return true if the table is accessible, else false
     * @param table the table for which to check accessibility
     */
    protected boolean isAccessibleTable(Table table) throws SQLException {

        if (!session.isAccessible(table.getName())) {
            return false;
        }

        return (table.isTemp() && table.tableType != Table.SYSTEM_TABLE)
               ? (table.getOwnerSessionId() == session.getId())
               : true;
    }

    /**
     * Creates a new primoidal system table with the specified name.
     * @return a new system table
     * @param name of the table
     * @throws SQLException if a database access error occurs
     */
    protected Table createBlankTable(HsqlName name) throws SQLException {
        return new Table(database, name, Table.SYSTEM_TABLE, 0);
    }

    /**
     * Retrieves the system <code>Table</code> object corresponding to
     * the given <code>name</code> and <code>session</code> arguments.
     * @param name a String identifying the desired table
     * @param session the Session object requesting the table
     * @throws SQLException if there is a problem producing the table or a
     *      database access error occurs
     * @return a system table corresponding to the <code>name</code> and
     *      <code>session</code> arguments
     */
    Table getSystemTable(String name, Session session) throws SQLException {

        Table t;
        int   tableIndex;

        Trace.doAssert(name != null, "name is null");
        Trace.doAssert(session != null, "session is null");

        // must come first...many methods depend on this being set properly
        this.session = session;
        t            = ns.findPubSchemaTable(name);

        if (t != null) {
            return t;
        }

        t = ns.findUserSchemaTable(name, session);

        if (t != null) {
            return t;
        }

        name = ns.withoutDefnSchema(name);

        // at this point, we still might have a "special" system table
        // that must be in database.tTable in order to get persisted...
        //
        // TODO:  Formalize system/interface for persistent SYSTEM tables
        //
        // Once the TODO is done, there needs to be code here, something like:
        //
        // if (persistedSystemTablesSet.contains(name)) {
        //      return database.findUserTable(name);
        // }
        if (!isSystemTable(name)) {
            return null;
        }

        tableIndex = sysTableNamesMap.get(name);
        t          = sysTables[tableIndex];

        // fredt - any system table that is not supported will be null here
        if (t == null) {
            return t;
        }

        // at the time of opening the database, no content is needed
        // at present, needed for view df'ns only
        if (!withContent) {
            return t;
        }

        StopWatch sw = null;

        if (Trace.TRACE) {
            sw = new StopWatch();
        }

        if (isDirty) {
            cacheClear();

            if (Trace.TRACE) {
                Trace.trace("System table cache cleared.");
            }
        }

        Session oldSession = sysTableSessions[tableIndex];
        boolean tableValid = oldSession != null;

        // user has changed and table is user-dependent
        if (session != oldSession && sysTableSessionDependent[tableIndex]) {
            tableValid = false;
        }

        if (nonCachedTablesSet.contains(name)) {
            tableValid = false;
        }

        // any valid cached table will be returned here
        if (tableValid) {
            return t;
        }

        // fredt - clear the contents of table and set new User
        t.clearAllRows();

        // problem:  now a session can hang around here
        // after it dissconnects.  solve.
        // It might be better to
        // have sysTableUsers[tableIndex] instead,
        // since rights are per user, not per session
        // and a session's user may change.
        // Currently, this is covered by settng
        // dirty on each new connect action, but
        // this would not be required if using
        // and array of User instead of Sesson.
        sysTableSessions[tableIndex] = session;

        // match and if found, generate.
        t = generateTable(tableIndex);

        // t will b null at this point, if this implementation
        // does not support the particular table
        if (Trace.TRACE) {
            Trace.trace("generated system table: " + name + " in "
                        + sw.elapsedTime() + " ms.");
        }

        // send back what we found or generated
        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the optimal
     * set of visible columns that uniquely identifies a row
     * for each accessible table defined within this database. <p>
     *
     * Each row describes a single column of the best row indentifier column
     * set for a particular table.  Each row has the following
     * columns: <p>
     *
     * <pre>
     * SCOPE          SMALLINT  scope of applicability
     * COLUMN_NAME    VARCHAR   simple name of the column
     * DATA_TYPE      SMALLINT  SQL data type from DITypes
     * TYPE_NAME      VARCHAR   canonical type name
     * COLUMN_SIZE    INTEGER   precision
     * BUFFER_LENGTH  INTEGER   transfer size in bytes, if definitely known
     * DECIMAL_DIGITS SMALLINT  scale  - fixed # of decimal digits
     * PSEUDO_COLUMN  SMALLINT  is this a pseudo column like an Oracle ROWID?
     * TABLE_CAT      VARCHAR   table catalog
     * TABLE_SCHEM    VARCHAR   simple name of table schema
     * TABLE_NAME     VARCHAR   simple table name
     * NULLABLE       SMALLINT  is column nullable?
     * IN_KEY         BOOLEAN   column belongs to a primary or alternate key?
     * </pre> <p>
     *
     * <b>Notes:</b><p>
     *
     * <code>jdbcDatabaseMetaData.getBestRowIdentifier</code> uses its
     * nullable parameter to filter the rows of this table in the following
     * manner: <p>
     *
     * If the nullable parameter is <code>false</code>, then rows are reported
     * only if, in addition to satisfying the other specified filter values,
     * the IN_KEY column value is TRUE. If the nullable parameter is
     * <code>true</code>, then the IN_KEY column value is ignored. <p>
     *
     * There is not yet infrastructure in place to make some of the ranking
     * descisions described below, and it is anticipated that mechanisms
     * upon which cost descisions could be based will change significantly over
     * the next few releases.  Hence, in the interest of simplicity and of not
     * making overly complex dependency on features that will almost certainly
     * change significantly in the near future, the current implementation,
     * while perfectly adequate for all but the most demanding or exacting
     * purposes, is actually sub-optimal in the strictest sense. <p>
     *
     * A description of the current implementation follows: <p>
     *
     * <b>DEFINTIONS:</b>  <p>
     *
     * <b>Alternate key</b> <p>
     *
     *  <UL>
     *   <LI> An attribute of a table that, by virtue of its having a set of
     *        columns that are both the full set of columns participating in a
     *        unique constraint or index and are all not null, yeilds the same
     *        selectability characteristic that would obtained by declaring a
     *        primary key on those same columns.
     *  </UL> <p>
     *
     * <b>Column set performance ranking</b> <p>
     *
     *  <UL>
     *  <LI> The ranking of the expected average performance w.r.t a subset of
     *       a table's columns used to select and/or compare rows, as taken in
     *       relation to all other distinct candidate subsets under
     *       consideration. This can be estimated by comparing each cadidate
     *       subset in terms of total column count, relative peformance of
     *       comparisons amongst the domains of the columns and differences
     *       in other costs involved in the execution plans generated using
     *       each subset under consideration for row selection/comparison.
     *  </UL> <p>
     *
     *
     * <b>Rules:</b> <p>
     *
     * Given the above definitions, the rules currently in effect for reporting
     * best row identifier are as follows, in order of precedence: <p>
     *
     * <OL>
     * <LI> if the table under consideration has a primary key contraint, then
     *      the columns of the primary key are reported, with no consideration
     *      given to the column set performance ranking over the set of
     *      candidate keys. Each row has its IN_KEY column set to TRUE.
     *
     * <LI> if 1.) does not hold, then if there exits one or more alternate
     *      keys, then the columns of the alternate key with the lowest column
     *      count are reported, with no consideration given to the column set
     *      performance ranking over the set of candidate keys. If there
     *      exists a tie for lowest column count, then the columns of the
     *      first such key encountered are reported.
     *      Each row has its IN_KEY column set to TRUE.
     *
     * <LI> if both 1.) and 2.) do not hold, then, if possible, a unique
     *      contraint/index is selected from the set of unique
     *      contraints/indices containing at least one column having
     *      a not null constraint, with no consideration given to the
     *      column set performance ranking over the set of all such
     *      candidate column sets. If there exists a tie for lowest non-zero
     *      count of columns having a not null constraint, then the columns
     *      of the first such encountered candidate set are reported. Each
     *      row has its IN_KEY column set to FALSE. <p>
     *
     * <LI> Finally, if the set of candidate column sets in 3.) is the empty,
     *      then no column set is reported for the table under consideration.
     * </OL> <p>
     *
     * The scope reported for a best row identifier column set is determined
     * thus: <p>
     *
     * <OL>
     * <LI> if the database containing the table under consideration is in
     *      read-only mode or the table under consideration is GLOBAL TEMPORARY
     *      ( a TEMP or TEMP TEXT table, in HSQLDB parlance), then the scope
     *      is reported as
     *      <code>java.sql.DatabaseMetaData.bestRowSession</code>.
     *
     * <LI> if 1.) does not hold, then the scope is reported as
     *      <code>java.sql.DatabaseMetaData.bestRowTemporary</code>.
     * </OL> <p>
     * @return a <code>Table</code> object describing the optimal
     * set of visible columns that uniquely identifies a row
     * for each accessible table defined within this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_BESTROWIDENTIFIER() throws SQLException {

        Table t = sysTables[SYSTEM_BESTROWIDENTIFIER];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_BESTROWIDENTIFIER]);

            addColumn(t, "SCOPE", SMALLINT, false);            // not null
            addColumn(t, "COLUMN_NAME", VARCHAR, false);       // not null
            addColumn(t, "DATA_TYPE", SMALLINT, false);        // not null
            addColumn(t, "TYPE_NAME", VARCHAR, false);         // not null
            addColumn(t, "COLUMN_SIZE", INTEGER);
            addColumn(t, "BUFFER_LENGTH", INTEGER);
            addColumn(t, "DECIMAL_DIGITS", SMALLINT);
            addColumn(t, "PSEUDO_COLUMN", SMALLINT, false);    // not null
            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);        // not null
            addColumn(t, "NULLABLE", SMALLINT, false);         // not null
            addColumn(t, "IN_KEY", BIT, false);                // not null

            // order: SCOPE
            // for unique:  TABLE_CAT, TABLE_SCHEM, TABLE_NAME, COLUMN_NAME
            t.createPrimaryKey(null, new int[] {
                0, 8, 9, 10, 1
            }, false);

            // fast lookup for metadat calls
            addIndex(t, null, new int[]{ 8 }, false);
            addIndex(t, null, new int[]{ 9 }, false);
            addIndex(t, null, new int[]{ 10 }, false);

            return t;
        }

        // calculated column values
        Integer scope;           // { temp, transaction, session }
        Integer pseudo;

        //-------------------------------------------
        // required for restriction of results via
        // DatabaseMetaData filter parameters, but
        // not actually  included in
        // DatabaseMetaData.getBestRowIdentifier()
        // result set
        //-------------------------------------------
        String  tableCatalog;    // table calalog
        String  tableSchema;     // table schema
        String  tableName;       // table name
        Boolean inKey;           // column participates in PK or AK?

        //-------------------------------------------
        // TODO:  Maybe include:
        //        - backing index (constraint) name?
        //        - column sequence in index (constraint)?
        //-------------------------------------------
        // Intermediate holders
        Enumeration tables;
        Table       table;
        DITableInfo ti;
        int[]       cols;
        Object[]    row;

        // Column number mappings
        final int iscope          = 0;
        final int icolumn_name    = 1;
        final int idata_type      = 2;
        final int itype_name      = 3;
        final int icolumn_size    = 4;
        final int ibuffer_length  = 5;
        final int idecimal_digits = 6;
        final int ipseudo_column  = 7;
        final int itable_cat      = 8;
        final int itable_schem    = 9;
        final int itable_name     = 10;
        final int inullable       = 11;
        final int iinKey          = 12;

        // Initialization
        ti = new DITableInfo();

        // all tables
        tables = allTables();

        // Do it.
        while (tables.hasMoreElements()) {
            table = (Table) tables.nextElement();

            if (!isAccessibleTable(table)) {
                continue;
            }

            cols = table.getBestRowIdentifiers();

            if (cols == null) {
                continue;
            }

            inKey = table.isBestRowIdentifiersStrict() ? Boolean.TRUE
                                                       : Boolean.FALSE;

            ti.setTable(table);

            tableName    = ti.getName();
            tableCatalog = ns.getCatalogName(table);
            tableSchema  = ns.getSchemaName(table);
            scope        = ti.getBRIScope();
            pseudo       = ti.getBRIPseudo();

            for (int i = 0; i < cols.length; i++) {
                row                  = t.getNewRow();
                row[iscope]          = scope;
                row[icolumn_name]    = ti.getColName(i);
                row[idata_type]      = ti.getColDataType(i);
                row[itype_name]      = ti.getColDataTypeName(i);
                row[icolumn_size]    = ti.getColSize(i);
                row[ibuffer_length]  = ti.getColBufLen(i);
                row[idecimal_digits] = ti.getColScale(i);
                row[ipseudo_column]  = pseudo;
                row[itable_cat]      = tableCatalog;
                row[itable_schem]    = tableSchema;
                row[itable_name]     = tableName;
                row[inullable]       = ti.getColNullability(i);
                row[iinKey]          = inKey;

                t.insert(row, session);
            }
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object naming the accessible catalogs
     * defined within this database. <p>
     *
     * Each row is a catalog name description with the following column: <p>
     *
     * <pre>
     * TABLE_CAT   VARCHAR   catalog name
     * </pre> <p>
     * @return a <code>Table</code> object naming the accessible
     *        catalogs defined within this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_CATALOGS() throws SQLException {

        Table t = sysTables[SYSTEM_CATALOGS];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_CATALOGS]);

            addColumn(t, "TABLE_CAT", VARCHAR, false);    // not null
            t.createPrimaryKey(null, new int[]{ 0 }, false);

            return t;
        }

        Object[]    row;
        Enumeration catalogs;
        String      catalogName;

        catalogs = ns.enumCatalogNames();

        while (catalogs.hasMoreElements()) {
            catalogName = (String) catalogs.nextElement();
            row         = t.getNewRow();
            row[0]      = catalogName;

            t.insert(row, session);
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the visible
     * access rights for all visible columns of all accessible
     * tables defined within this database.<p>
     *
     * Each row is a column privilege description with the following columns: <p>
     *
     * <pre>
     * TABLE_CAT    VARCHAR   table catalog
     * TABLE_SCHEM  VARCHAR   table schema
     * TABLE_NAME   VARCHAR   table name
     * COLUMN_NAME  VARCHAR   column name
     * GRANTOR      VARCHAR   grantor of access
     * GRANTEE      VARCHAR   grantee of access
     * PRIVILEGE    VARCHAR   name of access
     * IS_GRANTABLE VARCHAR   grantable?: YES - grant to others, else NO
     * </pre>
     *
     * <b>Note:</b> As of 1.7.2, HSQLDB does not support column level
     * privileges. However, it does support table-level privileges, so they
     * are reflected here.  That is, the content of this table is equivalent
     * to a projection of SYSTEM_TABLEPRIVILEGES SYSTYEM_COLUMNS joined by full
     * table identifier.
     * @return a <code>Table</code> object describing the visible
     *        access rights for all visible columns of
     *        all accessible tables defined within this
     *        database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_COLUMNPRIVILEGES() throws SQLException {

        Table t = sysTables[SYSTEM_COLUMNPRIVILEGES];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_COLUMNPRIVILEGES]);

            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);      // not null
            addColumn(t, "COLUMN_NAME", VARCHAR, false);     // not null
            addColumn(t, "GRANTOR", VARCHAR, false);         // not null
            addColumn(t, "GRANTEE", VARCHAR, false);         // not null
            addColumn(t, "PRIVILEGE", VARCHAR, false);       // not null
            addColumn(t, "IS_GRANTABLE", VARCHAR, false);    // not null

            // order: column_name, privilege,
            // for unique: grantee, grantor, table_name, table_schem, table_cat
            t.createPrimaryKey(null, new int[] {
                3, 6, 5, 4, 2, 1, 0
            }, false);

            // fast lookup for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);

            return t;
        }

        java.sql.ResultSet rs;

        // - used appends to make class file constant pool smaller
        // - saves 100 bytes on compressed size.
        rs = session.getInternalConnection().createStatement().executeQuery(
            (new StringBuffer()).append("select ").append("a.").append(
                "TABLE_CAT").append(", ").append("a.").append(
                "TABLE_SCHEM").append(", ").append("a.").append(
                "TABLE_NAME").append(", ").append("b.").append(
                "COLUMN_NAME").append(", ").append("a.").append(
                "GRANTOR").append(", ").append("a.").append("GRANTEE").append(
                ", ").append("a.").append("PRIVILEGE").append(", ").append(
                "a.").append("IS_GRANTABLE").append(" ").append(
                "from ").append("SYSTEM_TABLEPRIVILEGES").append(
                " a,").append("SYSTEM_COLUMNS").append(" b ").append(
                "where ").append("a.").append("TABLE_NAME").append(
                "=").append("b.").append("TABLE_NAME").toString());

        t.insert(((jdbcResultSet) rs).rResult, session);
        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the
     * visible columns of all accessible tables defined
     * within this database.<p>
     *
     * Each row is a column description with the following columns: <p>
     *
     * <pre>
     * TABLE_CAT         VARCHAR   table catalog
     * TABLE_SCHEM       VARCHAR   table schema
     * TABLE_NAME        VARCHAR   table name
     * COLUMN_NAME       VARCHAR   column name
     * DATA_TYPE         SMALLINT  SQL type from DITypes
     * TYPE_NAME         VARCHAR   canonical type name
     * COLUMN_SIZE       INTEGER   column size (length/precision)
     * BUFFER_LENGTH     INTEGER   transfer size in bytes, if definitely known
     * DECIMAL_DIGITS    INTEGER   # of fractional digits (scale)
     * NUM_PREC_RADIX    INTEGER   Radix
     * NULLABLE          INTEGER   is NULL allowed?
     * REMARKS           VARCHAR   comment describing column
     * COLUMN_DEF        VARCHAR   default value
     * SQL_DATA_TYPE     VARCHAR   type code as would be found in the SQL CLI SQLDA
     * SQL_DATETIME_SUB  INTEGER   the SQL CLI sub for DATETIME types
     * CHAR_OCTET_LENGTH INTEGER   for character types, maximum # of bytes in column
     * ORDINAL_POSITION  INTEGER   1-based index of column in table
     * IS_NULLABLE       VARCHAR   is column nullable?
     * SCOPE_CATLOG      VARCHAR   catalog of REF attribute scope table
     * SCOPE_SCHEMA      VARCHAR   schema of REF attribute scope table
     * SCOPE_TABLE       VARCHAR   name of REF attribute scope table
     * SOURCE_DATA_TYPE  VARCHAR   source type of REF attribute
     * <pre> <p>
     * @return a <code>Table</code> object describing the
     *        visible columns of all accessible
     *        tables defined within this database.<p>
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_COLUMNS() throws SQLException {

        Table t = sysTables[SYSTEM_COLUMNS];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_COLUMNS]);

            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);          // not null
            addColumn(t, "COLUMN_NAME", VARCHAR, false);         // not null
            addColumn(t, "DATA_TYPE", SMALLINT, false);          // not null
            addColumn(t, "TYPE_NAME", VARCHAR, false);           // not null
            addColumn(t, "COLUMN_SIZE", INTEGER);
            addColumn(t, "BUFFER_LENGTH", INTEGER);
            addColumn(t, "DECIMAL_DIGITS", INTEGER);
            addColumn(t, "NUM_PREC_RADIX", INTEGER);
            addColumn(t, "NULLABLE", INTEGER, false);            // not null
            addColumn(t, "REMARKS", VARCHAR);
            addColumn(t, "COLUMN_DEF", VARCHAR);
            addColumn(t, "SQL_DATA_TYPE", INTEGER);
            addColumn(t, "SQL_DATETIME_SUB", INTEGER);
            addColumn(t, "CHAR_OCTET_LENGTH", INTEGER);
            addColumn(t, "ORDINAL_POSITION", INTEGER, false);    // not null
            addColumn(t, "IS_NULLABLE", VARCHAR, false);         // not null
            addColumn(t, "SCOPE_CATLOG", VARCHAR);
            addColumn(t, "SCOPE_SCHEMA", VARCHAR);
            addColumn(t, "SCOPE_TABLE", VARCHAR);
            addColumn(t, "SOURCE_DATA_TYPE", VARCHAR);
            t.createPrimaryKey(null, new int[] {
                1, 2, 16
            }, false);

            // fast lookup for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);

            //addIndex(t, null, new int[]{1}, false);
            addIndex(t, null, new int[]{ 2 }, false);
            addIndex(t, null, new int[]{ 3 }, false);

            return t;
        }

        // calculated column values
        String tableCatalog;
        String tableSchema;
        String tableName;

        // intermediate holders
        int         columnCount;
        Enumeration tables;
        Table       table;
        Object[]    row;
        DITableInfo ti;

        // column number mappings
        final int itable_cat         = 0;
        final int itable_schem       = 1;
        final int itable_name        = 2;
        final int icolumn_name       = 3;
        final int idata_type         = 4;
        final int itype_name         = 5;
        final int icolumn_size       = 6;
        final int ibuffer_length     = 7;
        final int idecimal_digits    = 8;
        final int inum_prec_radix    = 9;
        final int inullable          = 10;
        final int iremark            = 11;
        final int icolumn_def        = 12;
        final int isql_data_type     = 13;
        final int isql_datetime_sub  = 14;
        final int ichar_octet_length = 15;
        final int iordinal_position  = 16;
        final int iis_nullable       = 17;

        // Initialization
        // all tables
        tables = allTables();
        ti     = new DITableInfo();

        // Do it.
        while (tables.hasMoreElements()) {
            table = (Table) tables.nextElement();

            if (!isAccessibleTable(table)) {
                continue;
            }

            ti.setTable(table);

            tableCatalog = ns.getCatalogName(table);
            tableSchema  = ns.getSchemaName(table);
            tableName    = ti.getName();
            columnCount  = table.getColumnCount();

            for (int i = 0; i < columnCount; i++) {
                row                     = t.getNewRow();
                row[itable_cat]         = tableCatalog;
                row[itable_schem]       = tableSchema;
                row[itable_name]        = tableName;
                row[icolumn_name]       = ti.getColName(i);
                row[idata_type]         = ti.getColDataType(i);
                row[itype_name]         = ti.getColDataTypeName(i);
                row[icolumn_size]       = ti.getColSize(i);
                row[ibuffer_length]     = ti.getColBufLen(i);
                row[idecimal_digits]    = ti.getColScale(i);
                row[inum_prec_radix]    = ti.getColPrecRadix(i);
                row[inullable]          = ti.getColNullability(i);
                row[iremark]            = ti.getColRemarks(i);
                row[icolumn_def]        = ti.getColDefault(i);
                row[isql_data_type]     = ti.getColSqlDataType(i);
                row[isql_datetime_sub]  = ti.getColSqlDateTimeSub(i);
                row[ichar_octet_length] = ti.getColCharOctLen(i);
                row[iordinal_position]  = ValuePool.getInt(i + 1);
                row[iis_nullable]       = ti.getColIsNullable(i);

                t.insert(row, session);
            }
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing for each
     * accessible rererencing and referenced table, how the referencing
     * tables import, for the purposes of referential integrity,
     * the columns of the referenced tables.<p>
     *
     * Each row is a foreign key column description with the following
     * columns: <p>
     *
     * <pre>
     * PKTABLE_CAT   VARCHAR   primary key table catalog
     * PKTABLE_SCHEM VARCHAR   primary key table schema
     * PKTABLE_NAME  VARCHAR   primary key table name
     * PKCOLUMN_NAME VARCHAR   primary key column name
     * FKTABLE_CAT   VARCHAR   foreign key table catalog being exported
     * FKTABLE_SCHEM VARCHAR   foreign key table schema being exported
     * FKTABLE_NAME  VARCHAR   foreign key table name being exported
     * FKCOLUMN_NAME VARCHAR   foreign key column name being exported
     * KEY_SEQ       SMALLINT  sequence number within foreign key
     * UPDATE_RULE   SMALLINT
     *    { Cascade | Set Null | Set Default | Restrict (No Action)}?
     * DELETE_RULE   SMALLINT
     *    { Cascade | Set Null | Set Default | Restrict (No Action)}?
     * FK_NAME       VARCHAR   foreign key name
     * PK_NAME       VARCHAR   primary key name
     * DEFERRABILITY SMALLINT
     *    { initially deferred | initially immediate | not deferrable }
     * <pre> <p>
     * @return a <code>Table</code> object describing how accessible tables import
     * other accessible tables' keys
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_CROSSREFERENCE() throws SQLException {

        Table t = sysTables[SYSTEM_CROSSREFERENCE];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_CROSSREFERENCE]);

            addColumn(t, "PKTABLE_CAT", VARCHAR);
            addColumn(t, "PKTABLE_SCHEM", VARCHAR);
            addColumn(t, "PKTABLE_NAME", VARCHAR, false);      // not null
            addColumn(t, "PKCOLUMN_NAME", VARCHAR, false);     // not null
            addColumn(t, "FKTABLE_CAT", VARCHAR);
            addColumn(t, "FKTABLE_SCHEM", VARCHAR);
            addColumn(t, "FKTABLE_NAME", VARCHAR, false);      // not null
            addColumn(t, "FKCOLUMN_NAME", VARCHAR, false);     // not null
            addColumn(t, "KEY_SEQ", SMALLINT, false);          // not null
            addColumn(t, "UPDATE_RULE", SMALLINT, false);      // not null
            addColumn(t, "DELETE_RULE", SMALLINT, false);      // not null
            addColumn(t, "FK_NAME", VARCHAR);
            addColumn(t, "PK_NAME", VARCHAR);
            addColumn(t, "DEFERRABILITY", SMALLINT, false);    // not null

            // order: FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, and KEY_SEQ
            t.createPrimaryKey(null, new int[] {
                4, 5, 6, 8, 11, 12
            }, false);

            // fast lookup for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);
            addIndex(t, null, new int[]{ 3 }, false);

            //addIndex(t, null, new int[]{4}, false);
            addIndex(t, null, new int[]{ 5 }, false);
            addIndex(t, null, new int[]{ 6 }, false);
            addIndex(t, null, new int[]{ 7 }, false);

            return t;
        }

        // calculated column values
        String  pkTableCatalog;
        String  pkTableSchema;
        String  pkTableName;
        String  pkColumnName;
        String  fkTableCatalog;
        String  fkTableSchema;
        String  fkTableName;
        String  fkColumnName;
        Integer keySequence;
        Integer updateRule;
        Integer deleteRule;
        String  fkName;
        String  pkName;
        Integer deferrability;

        // Intermediate holders
        Enumeration   tables;
        Table         table;
        Table         fkTable;
        Table         pkTable;
        int           columnCount;
        int[]         mainCols;
        int[]         refCols;
        HsqlArrayList constraints;
        Constraint    constraint;
        int           constraintCount;
        HsqlArrayList fkConstraintsList;
        Object[]      row;
        DITableInfo   pkInfo;
        DITableInfo   fkInfo;

        // column number mappings
        final int ipk_table_cat   = 0;
        final int ipk_table_schem = 1;
        final int ipk_table_name  = 2;
        final int ipk_column_name = 3;
        final int ifk_table_cat   = 4;
        final int ifk_table_schem = 5;
        final int ifk_table_name  = 6;
        final int ifk_column_name = 7;
        final int ikey_seq        = 8;
        final int iupdate_rule    = 9;
        final int idelete_rule    = 10;
        final int ifk_name        = 11;
        final int ipk_name        = 12;
        final int ideferrability  = 13;

        // TODO:
        // disallow DDL that creates references to system tables
        // Initialization
        tables = database.getTables().elements();
        pkInfo = new DITableInfo();
        fkInfo = new DITableInfo();

        // the only deferrability rule currently supported by hsqldb is:
        deferrability =
            ValuePool.getInt(DatabaseMetaData.importedKeyNotDeferrable);

        // We must consider all the constraints in all the user tables, since
        // this is where reference relationships are recorded.  However, we
        // are only concerned with Constraint.FOREIGN_KEY constraints here
        // because their corresponing Constraint.MAIN entries are essentially
        // duplicate data recorded in the referenced rather than the
        // referencing table.
        fkConstraintsList = new HsqlArrayList();

        while (tables.hasMoreElements()) {
            table = (Table) tables.nextElement();

            if (!isAccessibleTable(table)) {
                continue;
            }

            constraints     = table.getConstraints();
            constraintCount = constraints.size();

            for (int i = 0; i < constraintCount; i++) {
                constraint = (Constraint) constraints.get(i);

                if (constraint.getType() == Constraint.FOREIGN_KEY
                        && isAccessibleTable(constraint.getRef())) {
                    fkConstraintsList.add(constraint);
                }
            }
        }

        // Now that we have all of the desired constraints, we need to
        // process them, generating one row in our ouput table
        // for each column in each table participating in each constraint,
        // skipping constraints that refer to columns in tables to which the
        // session user has no access (may not make references)
        // Do it.
        for (int i = 0; i < fkConstraintsList.size(); i++) {
            constraint = (Constraint) fkConstraintsList.get(i);
            pkTable    = constraint.getMain();

            pkInfo.setTable(pkTable);

            pkTableName = pkInfo.getName();
            fkTable     = constraint.getRef();

            fkInfo.setTable(fkTable);

            fkTableName    = fkInfo.getName();
            pkTableCatalog = ns.getCatalogName(pkTable);
            pkTableSchema  = ns.getSchemaName(pkTable);
            fkTableCatalog = ns.getCatalogName(fkTable);
            fkTableSchema  = ns.getSchemaName(fkTable);
            mainCols       = constraint.getMainColumns();
            refCols        = constraint.getRefColumns();
            columnCount    = refCols.length;
            fkName         = constraint.getFkName();

            // CHECKME:
            // shouldn't this be what gives the correct name?:
            // pkName   = constraint.getPkName();
            pkName = constraint.getMainIndex().getName().name;

            switch (constraint.getDeleteAction()) {

                case Constraint.CASCADE :
                    deleteRule =
                        ValuePool.getInt(DatabaseMetaData.importedKeyCascade);
                    break;

                case Constraint.SET_DEFAULT :
                    deleteRule = ValuePool.getInt(
                        DatabaseMetaData.importedKeySetDefault);
                    break;

                case Constraint.SET_NULL :
                    deleteRule =
                        ValuePool.getInt(DatabaseMetaData.importedKeySetNull);
                    break;

                case Constraint.NO_ACTION :
                default :
                    deleteRule = ValuePool.getInt(
                        DatabaseMetaData.importedKeyNoAction);
            }

            switch (constraint.getUpdateAction()) {

                case Constraint.CASCADE :
                    updateRule =
                        ValuePool.getInt(DatabaseMetaData.importedKeyCascade);
                    break;

                case Constraint.SET_DEFAULT :
                    updateRule = ValuePool.getInt(
                        DatabaseMetaData.importedKeySetDefault);
                    break;

                case Constraint.SET_NULL :
                    updateRule =
                        ValuePool.getInt(DatabaseMetaData.importedKeySetNull);
                    break;

                case Constraint.NO_ACTION :
                default :
                    updateRule = ValuePool.getInt(
                        DatabaseMetaData.importedKeyNoAction);
            }

            for (int j = 0; j < columnCount; j++) {
                keySequence          = ValuePool.getInt(j + 1);
                pkColumnName         = pkInfo.getColName(mainCols[j]);
                fkColumnName         = fkInfo.getColName(refCols[j]);
                row                  = t.getNewRow();
                row[ipk_table_cat]   = pkTableCatalog;
                row[ipk_table_schem] = pkTableSchema;
                row[ipk_table_name]  = pkTableName;
                row[ipk_column_name] = pkColumnName;
                row[ifk_table_cat]   = fkTableCatalog;
                row[ifk_table_schem] = fkTableSchema;
                row[ifk_table_name]  = fkTableName;
                row[ifk_column_name] = fkColumnName;
                row[ikey_seq]        = keySequence;
                row[iupdate_rule]    = updateRule;
                row[idelete_rule]    = deleteRule;
                row[ifk_name]        = fkName;
                row[ipk_name]        = pkName;
                row[ideferrability]  = deferrability;

                t.insert(row, session);
            }
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the visible
     * <code>Index</code> objects for each accessible table defined
     * within this database.<p>
     *
     * Each row is an index column description with the following
     * columns: <p>
     *
     * <PRE>
     * TABLE_CAT        VARCHAR   catalog in which the table using the index is defined
     * TABLE_SCHEM      VARCHAR   schema in which the table using the index is defined
     * TABLE_NAME       VARCHAR   simple name of the table using the index
     * NON_UNIQUE       BIT       can index values be non-unique?
     * INDEX_QUALIFIER  VARCHAR   catalog in which the index is defined
     * INDEX_NAME       VARCHAR   simple name of the index
     * TYPE             SMALLINT  index type: one of { Clustered | Hashed | Other }
     * ORDINAL_POSITION SMALLINT  column sequence number within index
     * COLUMN_NAME      VARCHAR   simple column name
     * ASC_OR_DESC      VARCHAR   column sort sequence: { "A" (Asc.) | "D" (Desc.) }
     * CARDINALITY      INTEGER   # of unique values in index (not implemented)
     * PAGES            INTEGER   index page use (not implemented)
     * FILTER_CONDITION VARCHAR   filter condition, if any (not implemented)
     * </PRE>
     * @return a <code>Table</code> object describing the visible
     *        <code>Index</code> objects for each accessible
     *        table defined within this database.
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_INDEXINFO() throws SQLException {

        Table t = sysTables[SYSTEM_INDEXINFO];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_INDEXINFO]);

            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);    // NOT NULL
            addColumn(t, "NON_UNIQUE", BIT, false);        // NOT NULL
            addColumn(t, "INDEX_QUALIFIER", VARCHAR);
            addColumn(t, "INDEX_NAME", VARCHAR);
            addColumn(t, "TYPE", SMALLINT, false);         // NOT NULL
            addColumn(t, "ORDINAL_POSITION", SMALLINT);
            addColumn(t, "COLUMN_NAME", VARCHAR);
            addColumn(t, "ASC_OR_DESC", VARCHAR);
            addColumn(t, "CARDINALITY", INTEGER);
            addColumn(t, "PAGES", INTEGER);
            addColumn(t, "FILTER_CONDITION", VARCHAR);

            // order: NON_UNIQUE, TYPE, INDEX_NAME, and ORDINAL_POSITION.
            t.createPrimaryKey(null, new int[] {
                3, 6, 5, 7
            }, true);

            // fast lookup for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);

            //addIndex(t, null, new int[]{3}, false);
            addIndex(t, null, new int[]{ 5 }, false);

            return t;
        }

        // calculated column values
        String  tableCatalog;
        String  tableSchema;
        String  tableName;
        Boolean nonUnique;
        String  indexQualifier;
        String  indexName;
        Integer indexType;

        //Integer ordinalPosition;
        //String  columnName;
        //String  ascOrDesc;
        Integer cardinality;
        Integer pages;
        String  filterCondition;

        // Intermediate holders
        Enumeration tables;
        Table       table;
        int         indexCount;
        int[]       cols;
        int         col;
        int         colCount;
        Object      row[];
        DITableInfo ti;

        // column number mappings
        final int itable_cat        = 0;
        final int itable_schem      = 1;
        final int itable_name       = 2;
        final int inon_unique       = 3;
        final int iindex_qualifier  = 4;
        final int iindex_name       = 5;
        final int itype             = 6;
        final int iordinal_position = 7;
        final int icolumn_name      = 8;
        final int iasc_or_desc      = 9;
        final int icardinality      = 10;
        final int ipages            = 11;
        final int ifilter_condition = 12;

        // Initialization
        tables = allTables();
        ti     = new DITableInfo();

        // Do it.
        while (tables.hasMoreElements()) {
            table = (Table) tables.nextElement();

            if (!isAccessibleTable(table)) {
                continue;
            }

            ti.setTable(table);

            tableCatalog = ns.getCatalogName(table);
            tableSchema  = ns.getSchemaName(table);
            tableName    = ti.getName();

            // not supported yet
            filterCondition = null;
            indexCount      = table.getIndexCount();

            // process all of the visible indicies for this table
            for (int i = 0; i < indexCount; i++) {
                indexName = ti.getIndexName(i);

                // different cat for index not supported yet
                indexQualifier = tableCatalog;
                nonUnique      = ti.isIndexNonUnique(i);
                cardinality    = ti.getIndexCardinality(i);
                pages          = ti.getIndexPages(i);
                cols           = ti.getIndexColumns(i);
                colCount       = ti.getIndexVisibleColumns(i);
                indexType      = ti.getIndexType(i);

                for (int k = 0; k < colCount; k++) {
                    col                    = cols[k];
                    row                    = t.getNewRow();
                    row[itable_cat]        = tableCatalog;
                    row[itable_schem]      = tableSchema;
                    row[itable_name]       = tableName;
                    row[inon_unique]       = nonUnique;
                    row[iindex_qualifier]  = indexQualifier;
                    row[iindex_name]       = indexName;
                    row[itype]             = indexType;
                    row[iordinal_position] = ValuePool.getInt(k + 1);
                    row[icolumn_name]      = ti.getColName(col);
                    row[iasc_or_desc]      = ti.getIndexColDirection(i, col);
                    row[icardinality]      = cardinality;
                    row[ipages]            = pages;
                    row[ifilter_condition] = filterCondition;

                    t.insert(row, session);
                }
            }
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the visible
     * primary key columns of each accessible table defined within
     * this database. <p>
     *
     * Each row is a PRIMARY KEY column description with the following
     * columns: <p>
     *
     * <pre>
     * TABLE_CAT   VARCHAR   table catalog
     * TABLE_SCHEM VARCHAR   table schema
     * TABLE_NAME  VARCHAR   table name
     * COLUMN_NAME VARCHAR   column name
     * KEY_SEQ     SMALLINT  sequence number within primary key
     * PK_NAME     VARCHAR   primary key name
     * </pre>
     * @return a <code>Table</code> object describing the visible
     *        primary key columns of each accessible table
     *        defined within this database.
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_PRIMARYKEYS() throws SQLException {

        Table t = sysTables[SYSTEM_PRIMARYKEYS];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_PRIMARYKEYS]);

            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);     // not null
            addColumn(t, "COLUMN_NAME", VARCHAR, false);    // not null
            addColumn(t, "KEY_SEQ", SMALLINT, false);       // not null
            addColumn(t, "PK_NAME", VARCHAR);

            // order: COLUMN_NAME (table_name required for unique)
            t.createPrimaryKey(null, new int[] {
                3, 2
            }, false);

            // fast lookups for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);

            //addIndex(t, null, new int[]{3}, false);
            addIndex(t, null, new int[]{ 5 }, false);

            return t;
        }

        // calculated column values
        String tableCatalog;
        String tableSchema;
        String tableName;

        //String  columnName;
        //Integer keySequence;
        String primaryKeyName;

        // Intermediate holders
        Enumeration tables;
        Table       table;
        Object[]    row;
        Index       index;
        int[]       cols;
        int         colCount;
        DITableInfo ti;

        // column number mappings
        final int itable_cat   = 0;
        final int itable_schem = 1;
        final int itable_name  = 2;
        final int icolumn_name = 3;
        final int ikey_seq     = 4;
        final int ipk_name     = 5;

        // Initialization
        tables = allTables();
        ti     = new DITableInfo();

        while (tables.hasMoreElements()) {
            table = (Table) tables.nextElement();
            index = table.getPrimaryIndex();

            if (index == null) {
                continue;
            }

            ti.setTable(table);

            if (!isAccessibleTable(table) ||!ti.isPrimaryIndexPrimaryKey()) {
                continue;
            }

            tableCatalog   = ns.getCatalogName(table);
            tableSchema    = ns.getSchemaName(table);
            tableName      = ti.getName();
            primaryKeyName = index.getName().name;
            cols           = index.getColumns();
            colCount       = cols.length;

            for (int j = 0; j < colCount; j++) {
                row               = t.getNewRow();
                row[itable_cat]   = tableCatalog;
                row[itable_schem] = tableSchema;
                row[itable_name]  = tableName;
                row[icolumn_name] = ti.getColName(cols[j]);
                row[ikey_seq]     = ValuePool.getInt(j + 1);
                row[ipk_name]     = primaryKeyName;

                t.insert(row, session);
            }
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the
     * return, parameter and result columns of the accessible
     * routines defined within this database.<p>
     *
     * Each row is a procedure column description with the following
     * columns: <p>
     *
     * <pre>
     * PROCEDURE_CAT   VARCHAR   procedure catalog
     * PROCEDURE_SCHEM VARCHAR   procedure schema
     * PROCEDURE_NAME  VARCHAR   procedure name
     * COLUMN_NAME     VARCHAR   column/parameter name
     * COLUMN_TYPE     SMALLINT  kind of column/parameter
     * DATA_TYPE       SMALLINT  SQL type from java.sql.Types
     * TYPE_NAME       VARCHAR   SQL type name
     * PRECISION       INTEGER   precision (length) of type
     * LENGTH          INTEGER   length--in bytes--of data
     * SCALE           SMALLINT  scale
     * RADIX           SMALLINT  radix
     * NULLABLE        SMALLINT  can column contain NULL?
     * REMARKS         VARCHAR   comment on { return value | parameter | result column }
     * </pre> <p>
     * @return a <code>Table</code> object describing the
     *        return, parameter and result columns
     *        of the accessible routines defined
     *        within this database.
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_PROCEDURECOLUMNS() throws SQLException {

        Table t = sysTables[SYSTEM_PROCEDURECOLUMNS];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_PROCEDURECOLUMNS]);

            // ----------------------------------------------------------------
            // required
            // ----------------------------------------------------------------
            addColumn(t, "PROCEDURE_CAT", VARCHAR);
            addColumn(t, "PROCEDURE_SCHEM", VARCHAR);
            addColumn(t, "PROCEDURE_NAME", VARCHAR, false);    // not null
            addColumn(t, "COLUMN_NAME", VARCHAR, false);       // not null
            addColumn(t, "COLUMN_TYPE", SMALLINT, false);      // not null
            addColumn(t, "DATA_TYPE", SMALLINT, false);        // not null
            addColumn(t, "TYPE_NAME", VARCHAR, false);         // not null
            addColumn(t, "PRECISION", INTEGER);
            addColumn(t, "LENGTH", INTEGER);
            addColumn(t, "SCALE", SMALLINT);
            addColumn(t, "RADIX", SMALLINT);
            addColumn(t, "NULLABLE", SMALLINT, false);         // not null
            addColumn(t, "REMARKS", VARCHAR);

            // ----------------------------------------------------------------
            // extended
            // ----------------------------------------------------------------
            addColumn(t, "SIGNATURE", VARCHAR);

            // ----------------------------------------------------------------
            // required for JDBC sort contract
            // ----------------------------------------------------------------
            addColumn(t, "SEQ", INTEGER);

            // ----------------------------------------------------------------
            // order: PROCEDURE_SCHEM and PROCEDURE_NAME.
            // sig & seq added for unique
            t.createPrimaryKey(null, new int[] {
                1, 2, 13, 14
            }, false);

            // fast lookup for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);

            // fast join with SYSTEM_PROCEDURES
            addIndex(t, null, new int[]{ 13 }, false);

            return t;
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the accessible
     * routines defined within the this database.
     *
     * Each row is a procedure description with the following
     * columns: <p>
     *
     * <pre>
     * PROCEDURE_CAT     VARCHAR   catalog in which procedure is defined
     * PROCEDURE_SCHEM   VARCHAR   schema in which procedure is defined
     * PROCEDURE_NAME    VARCHAR   procedure identifier
     * NUM_INPUT_PARAMS  INTEGER   number of procedure input parameters
     * NUM_OUTPUT_PARAMS INTEGER   number of procedure output parameters
     * NUM_RESULT_SETS   INTEGER   number of result sets returned by procedure
     * REMARKS           VARCHAR   explanatory comment on the procedure
     * PROCEDURE_TYPE    SMALLINT  kind of procedure: { Unknown | No Result | Returns Result }
     * ORIGIN            VARCHAR   { ALIAS | ([BUILTIN | USER DEFINED] ROUTINE | TRIGGER | ...)}
     * SIGNATURE         VARCHAR   typically, but not restricted to a Java Method signature
     * </pre>
     * @return a <code>Table</code> object describing the accessible
     *        routines defined within the this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_PROCEDURES() throws SQLException {

        Table t = sysTables[SYSTEM_PROCEDURES];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_PROCEDURES]);

            // ----------------------------------------------------------------
            // required
            // ---------------------------------------------------------------
            addColumn(t, "PROCEDURE_CAT", VARCHAR);
            addColumn(t, "PROCEDURE_SCHEM", VARCHAR);
            addColumn(t, "PROCEDURE_NAME", VARCHAR, false);     // not null
            addColumn(t, "NUM_INPUT_PARAMS", INTEGER);
            addColumn(t, "NUM_OUTPUT_PARAMS", INTEGER);
            addColumn(t, "NUM_RESULT_SETS", INTEGER);
            addColumn(t, "REMARKS", VARCHAR);

            // basically: funtion, procedure or
            // unknown( say, a trigger callout routine)
            addColumn(t, "PROCEDURE_TYPE", SMALLINT, false);    // not null

            // ----------------------------------------------------------------
            // extended
            // ----------------------------------------------------------------
            addColumn(t, "ORIGIN", VARCHAR, false);             // not null
            addColumn(t, "SIGNATURE", VARCHAR, false);          // not null

            // ----------------------------------------------------------------
            // order: PROCEDURE_SCHEM and PROCEDURE_NAME.
            // sig added for uniqe
            t.createPrimaryKey(null, new int[] {
                1, 2, 9
            }, false);

            // fast lookup for metadata calls
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);

            // fast join with SYSTEM_PROCEDURECOLUMNS
            addIndex(t, null, new int[]{ 9 }, false);

            return t;
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * <P>Retrieves a tabular description of the schemas accessible within the
     * specified <code>Session</code> context. <p>
     *
     * Each row is a schema description with the following
     * columns: <p>
     *
     * <pre>
     * TABLE_SCHEM   VARCHAR   simple schema name
     * TABLE_CATALOG VARCHAR   catalog in which schema is defined
     * </pre>
     * @return table containing information about schemas defined within the database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_SCHEMAS() throws SQLException {

        Table t = sysTables[SYSTEM_SCHEMAS];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_SCHEMAS]);

            addColumn(t, "TABLE_SCHEM", VARCHAR, false);    // not null
            addColumn(t, "TABLE_CATALOG", VARCHAR);

            // order: TABLE_SCHEM
            // true PK
            t.createPrimaryKey(null, new int[]{ 0 }, false);

            return t;
        }

        Enumeration schemas;
        Object[]    row;

        // Initialization
        schemas = ns.enumVisibleSchemaNames(session);

        // Do it.
        while (schemas.hasMoreElements()) {
            row    = t.getNewRow();
            row[0] = schemas.nextElement();
            row[1] = ns.getCatalogName(row[0]);

            t.insert(row, session);
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the visible access
     * rights for each accessible table definied within this database. <p>
     *
     * Each row is a table privilege description with the following columns: <p>
     *
     * <pre>
     * TABLE_CAT    VARCHAR   table catalog
     * TABLE_SCHEM  VARCHAR   table schema
     * TABLE_NAME   VARCHAR   table name
     * GRANTOR      VARCHAR   grantor of access
     * GRANTEE      VARCHAR   grantee of access
     * PRIVILEGE    VARCHAR   { SELECT | INSERT | UPDATE | DELETE }
     * IS_GRANTABLE VARCHAR   { YES | NO |  NULL (unknown) }
     * </pre>
     *
     * <b>Note:</b> Up to and including HSQLDB 1.7.2, the access rights granted
     * on a table apply to all of the columns of that table as well. <p>
     * @return a <code>Table</code> object describing the visible
     *        access rights for each accessible table
     *        definied within this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_TABLEPRIVILEGES() throws SQLException {

        Table t = sysTables[SYSTEM_TABLEPRIVILEGES];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_TABLEPRIVILEGES]);

            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);      // not null
            addColumn(t, "GRANTOR", VARCHAR, false);         // not null
            addColumn(t, "GRANTEE", VARCHAR, false);         // not null
            addColumn(t, "PRIVILEGE", VARCHAR, false);       // not null
            addColumn(t, "IS_GRANTABLE", VARCHAR, false);    // not null

            // order: TABLE_SCHEM, TABLE_NAME, and PRIVILEGE,
            // grantee, grantor added for unique
            // false primary key, as schema may be null
            t.createPrimaryKey(null, new int[] {
                1, 2, 5, 4, 3
            }, false);

            // fast lookup by (table,grantee)
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);

            //addIndex(t, null, new int[]{2},false);
            addIndex(t, null, new int[]{ 4 }, false);

            return t;
        }

        // calculated column values
        String tableCatalog;
        String tableSchema;
        String tableName;
        String grantorName;
        String granteeName;
        String privilege;
        String isGrantable;

        // Intermediate holders
        HsqlArrayList users;
        User          user;
        HsqlArrayList tablePrivileges;
        Enumeration   tables;
        Table         table;
        HsqlName      accessKey;
        Object[]      row;

        // column number mappings
        final int itable_cat    = 0;
        final int itable_schem  = 1;
        final int itable_name   = 2;
        final int igrantor      = 3;
        final int igrantee      = 4;
        final int iprivilege    = 5;
        final int iis_grantable = 6;

        // Initialization
        grantorName = UserManager.SYS_USER_NAME;
        users = database.getUserManager().listVisibleUsers(session, true);
        tables      = allTables();

        // Do it.
        while (tables.hasMoreElements()) {
            table     = (Table) tables.nextElement();
            accessKey = table.getName();

            // Only show table grants if session user is admin, has some
            // right, or the special PUBLIC user has some right.
            if (!isAccessibleTable(table)) {
                continue;
            }

            tableName    = table.getName().name;
            tableCatalog = ns.getCatalogName(table);
            tableSchema  = ns.getSchemaName(table);

            for (int i = 0; i < users.size(); i++) {
                user            = (User) users.get(i);
                granteeName     = user.getName();
                tablePrivileges = user.listTablePrivileges(accessKey);
                isGrantable     = (user.isAdmin()) ? "YES"
                                                   : "NO";

                for (int j = 0; j < tablePrivileges.size(); j++) {
                    privilege          = (String) tablePrivileges.get(j);
                    row                = t.getNewRow();
                    row[itable_cat]    = tableCatalog;
                    row[itable_schem]  = tableSchema;
                    row[itable_name]   = tableName;
                    row[igrantor]      = grantorName;
                    row[igrantee]      = granteeName;
                    row[iprivilege]    = privilege;
                    row[iis_grantable] = isGrantable;

                    t.insert(row, session);
                }
            }
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the accessible
     * tables defined within this database. <p>
     *
     * Each row is a table description with the following columns: <p>
     *
     * <pre>
     * TABLE_CAT                 VARCHAR   table catalog
     * TABLE_SCHEM               VARCHAR   table schema
     * TABLE_NAME                VARCHAR   table name
     * TABLE_TYPE                VARCHAR   { TABLE | VIEW | SYSTEM TABLE | GLOBAL TEMPORARY }
     * REMARKS                   VARCHAR   comment on the table
     * TYPE_CAT                  VARCHAR   table type catalog (not implemented)
     * TYPE_SCHEM                VARCHAR   table type schema (not implemented)
     * TYPE_NAME                 VARCHAR   table type name (not implemented)
     * SELF_REFERENCING_COL_NAME VARCHAR   designated "identifier" column of typed table (not implemented)
     * REF_GENERATION            VARCHAR   { "SYSTEM" | "USER" | "DERIVED" | NULL } (not implemented)
     * NEXT_IDENTITY             INTEGER   next value for identity column.  NULL if no identity column.
     * READ_ONLY                 BIT       TRUE if table is read-only, else FALSE
     * HSQLDB_TYPE               VARCHAR   HSQLDB-specific type ( MEMORY | CACHED | TEXT | ...)
     * CACHE_FILE                VARCHAR   CACHED: file underlying table's Cache object.
     *                                    TEXT: the table's underlying CSV file
     * DATA_SOURCE               VARCHAR   TEXT: "spec" part of 'SET TABLE ident SOURCE "spec" [DESC]'
     * IS_DESC                   BIT       for TEXT tables, true if [DESC] set, else false.
     * </pre>
     * @return a <code>Table</code> object describing the accessible
     * tables defined within this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_TABLES() throws SQLException {

        Table t = sysTables[SYSTEM_TABLES];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_TABLES]);

            // -------------------------------------------------------------
            // required
            // -------------------------------------------------------------
            addColumn(t, "TABLE_CAT", VARCHAR);
            addColumn(t, "TABLE_SCHEM", VARCHAR);
            addColumn(t, "TABLE_NAME", VARCHAR, false);    // not null
            addColumn(t, "TABLE_TYPE", VARCHAR, false);    // not null
            addColumn(t, "REMARKS", VARCHAR);

            // -------------------------------------------------------------
            // JDBC3
            // -------------------------------------------------------------
            addColumn(t, "TYPE_CAT", VARCHAR);
            addColumn(t, "TYPE_SCHEM", VARCHAR);
            addColumn(t, "TYPE_NAME", VARCHAR);
            addColumn(t, "SELF_REFERENCING_COL_NAME", VARCHAR);
            addColumn(t, "REF_GENERATION", VARCHAR);

            // -------------------------------------------------------------
            // extended
            // ------------------------------------------------------------
            addColumn(t, "NEXT_IDENTITY", INTEGER);
            addColumn(t, "READ_ONLY", BIT);
            addColumn(t, "HSQLDB_TYPE", VARCHAR);
            addColumn(t, "CACHE_FILE", VARCHAR);
            addColumn(t, "DATA_SOURCE", VARCHAR);
            addColumn(t, "IS_DESC", BIT);

            // ------------------------------------------------------------
            // order TABLE_TYPE, TABLE_SCHEM and TABLE_NAME
            // false PK, as schema may be null
            t.createPrimaryKey(null, new int[] {
                3, 1, 2
            }, false);

            // fast lookup by table ident
            addIndex(t, null, new int[]{ 0 }, false);
            addIndex(t, null, new int[]{ 1 }, false);
            addIndex(t, null, new int[]{ 2 }, false);

            return t;
        }

        // Intermediate holders
        Enumeration tables;
        Table       table;
        Object      row[];
        HsqlName    accessKey;
        DITableInfo ti;

        // column number mappings
        final int itable_cat   = 0;
        final int itable_schem = 1;
        final int itable_name  = 2;
        final int itable_type  = 3;
        final int iremark      = 4;
        final int itype_cat    = 5;
        final int itype_schem  = 6;
        final int itype_name   = 7;
        final int isref_cname  = 8;
        final int iref_gen     = 9;
        final int inext_id     = 10;
        final int iread_only   = 11;
        final int ihsqldb_type = 12;
        final int icache_file  = 13;
        final int idata_source = 14;
        final int iis_desc     = 15;

        // Initialization
        tables = allTables();
        ti     = new DITableInfo();

        // Do it.
        while (tables.hasMoreElements()) {
            table = (Table) tables.nextElement();

            if (!isAccessibleTable(table)) {
                continue;
            }

            ti.setTable(table);

            row               = t.getNewRow();
            row[itable_cat]   = ns.getCatalogName(table);
            row[itable_schem] = ns.getSchemaName(table);
            row[itable_name]  = ti.getName();
            row[itable_type]  = ti.getStandardType();
            row[iremark]      = ti.getRemark();
            row[inext_id]     = ti.getNextIdentity();
            row[iread_only]   = ti.isReadOnly();
            row[ihsqldb_type] = ti.getHsqlType();
            row[icache_file]  = ti.getCachePath();
            row[idata_source] = ti.getDataSource();
            row[iis_desc]     = ti.isDataSourceDescending();

            t.insert(row, session);
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the table types
     * available in this database. <p>
     *
     * In general, the range of values that may be commonly encounted across
     * most DBMS implementations is: <p>
     *
     * <UL>
     *   <LI><FONT color='#FF00FF'>"TABLE"</FONT>
     *   <LI><FONT color='#FF00FF'>"VIEW"</FONT>
     *   <LI><FONT color='#FF00FF'>"SYSTEM TABLE"</FONT>
     *   <LI><FONT color='#FF00FF'>"GLOBAL TEMPORARY"</FONT>
     *   <LI><FONT color='#FF00FF'>"LOCAL TEMPORARY"</FONT>
     *   <LI><FONT color='#FF00FF'>"ALIAS"</FONT>
     *   <LI><FONT color='#FF00FF'>"SYNONYM"</FONT>
     * </UL> <p>
     *
     * As of HSQLDB 1.7.2, the engine supports and thus reports only a subset
     * of this range: <p>
     *
     * <UL>
     *   <LI><FONT color='#FF00FF'>"TABLE"</FONT>
     *    (HSQLDB MEMORY, CACHED and TEXT tables)
     *   <LI><FONT color='#FF00FF'>"VIEW"</FONT>  (Views)
     *   <LI><FONT color='#FF00FF'>"SYSTEM TABLE"</FONT>
     *    (The tables generated by this object)
     *   <LI><FONT color='#FF00FF'>"GLOBAL TEMPORARY"</FONT>
     *    (HSQLDB TEMP and TEMP TEXT tables)
     * </UL> <p>
     * @return a <code>Table</code> object describing the table types
     *        available in this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_TABLETYPES() throws SQLException {

        Table t = sysTables[SYSTEM_TABLETYPES];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_TABLETYPES]);

            addColumn(t, "TABLE_TYPE", VARCHAR, false);    // not null

            // order: TABLE_TYPE
            // true PK
            t.createPrimaryKey(null, new int[]{ 0 }, true);

            return t;
        }

        Object[] row;

        for (int i = 0; i < tableTypes.length; i++) {
            row    = t.getNewRow();
            row[0] = tableTypes[i];

            t.insert(row, session);
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the
     * JDBC-expected result for system-defined SQL types
     * supported as table columns.
     *
     * <pre>
     * TYPE_NAME          VARCHAR   the canonical name used DDL statements.
     * DATA_TYPE          SMALLINT  data type code from DITypes
     * PRECISION          INTEGER   max column size.
     *                              number => max. precision.
     *                              character => max characters.
     *                              datetime => max chars, incl. fract. component.
     * LITERAL_PREFIX     VARCHAR   char(s) prefixing literal of this type;
     * LITERAL_SUFFIX     VARCHAR   char(s) terminating literal of this type;
     * CREATE_PARAMS      VARCHAR   Localized syntax order list of domain parameter keywords.
     * NULLABLE           SMALLINT  { No Nulls | Nullable | Unknown }
     * CASE_SENSITIVE     BIT       case-sensitive in collations and comparisons?
     * SEARCHABLE         SMALLINT  { None | Char (Only WHERE .. LIKE) | Basic (Except WHERE .. LIKE) | Searchable (All forms) }
     * UNSIGNED_ATTRIBUTE BIT       { TRUE  (unsigned) | FALSE (signed) | NULL (non-numeric or not applicable) }
     * FIXED_PREC_SCALE   BIT       { TRUE (fixed) | FALSE (variable) | NULL (non-numeric or not applicable) }
     * AUTO_INCREMENT     BIT       if TRUE, then automatic unique inserted when no value or NULL specified
     * LOCAL_TYPE_NAME    VARCHAR   Localized name of data type; NULL => not supported.
     * MINIMUM_SCALE      SMALLINT  minimum scale supported
     * MAXIMUM_SCALE      SMALLINT  maximum scale supported
     * SQL_DATA_TYPE      INTEGER   value of SQL CLI SQL_DESC_TYPE field in the SQLDA
     * SQL_DATETIME_SUB   INTEGER   SQL datetime/interval subcode
     * NUM_PREC_RADIX     INTEGER   base w.r.t # of digits reported in PRECISION column
     * TYPE_SUB           INTEGER   { 1 (standard) | 2 (identity) | 4 (ignore case) }
     * </pre>
     * @return a <code>Table</code> object describing the
     *      system-defined SQL types supported as table columns
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_TYPEINFO() throws SQLException {

        Table t = sysTables[SYSTEM_TYPEINFO];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_TYPEINFO]);

            //-------------------------------------------
            // required by JDBC:
            // ------------------------------------------
            addColumn(t, "TYPE_NAME", VARCHAR, false);
            addColumn(t, "DATA_TYPE", SMALLINT, false);
            addColumn(t, "PRECISION", INTEGER);
            addColumn(t, "LITERAL_PREFIX", VARCHAR);
            addColumn(t, "LITERAL_SUFFIX", VARCHAR);
            addColumn(t, "CREATE_PARAMS", VARCHAR);
            addColumn(t, "NULLABLE", SMALLINT);
            addColumn(t, "CASE_SENSITIVE", BIT);
            addColumn(t, "SEARCHABLE", SMALLINT);
            addColumn(t, "UNSIGNED_ATTRIBUTE", BIT);
            addColumn(t, "FIXED_PREC_SCALE", BIT);
            addColumn(t, "AUTO_INCREMENT", BIT);
            addColumn(t, "LOCAL_TYPE_NAME", VARCHAR);
            addColumn(t, "MINIMUM_SCALE", SMALLINT);
            addColumn(t, "MAXIMUM_SCALE", SMALLINT);
            addColumn(t, "SQL_DATA_TYPE", INTEGER);
            addColumn(t, "SQL_DATETIME_SUB", INTEGER);
            addColumn(t, "NUM_PREC_RADIX", INTEGER);

            //-------------------------------------------
            // for JDBC sort contract:
            //-------------------------------------------
            addColumn(t, "TYPE_SUB", INTEGER);
            t.createPrimaryKey(null, new int[] {
                1, 18
            }, true);

            return t;
        }

        java.sql.ResultSet rs;

        rs = session.getInternalConnection().createStatement().executeQuery(
            "select " + "TYPE_NAME, " + "DATA_TYPE, " + "PRECISION, "
            + "LITERAL_PREFIX, " + "LITERAL_SUFFIX, " + "CREATE_PARAMS, "
            + "NULLABLE, " + "CASE_SENSITIVE, " + "SEARCHABLE, "
            + "UNSIGNED_ATTRIBUTE, " + "FIXED_PREC_SCALE, "
            + "AUTO_INCREMENT, " + "LOCAL_TYPE_NAME, " + "MINIMUM_SCALE, "
            + "MAXIMUM_SCALE, " + "SQL_DATA_TYPE, " + "SQL_DATETIME_SUB, "
            + "NUM_PREC_RADIX, " + "TYPE_SUB " + "from "
            + "SYSTEM_ALLTYPEINFO " + "where " + "AS_TAB_COL = true");

        t.insert(((jdbcResultSet) rs).rResult, session);
        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing,  in an extended
     * fashion, all of the standard (not user-defined) SQL types known to
     * this database, including its level of support for them (which may
     * be no support at all). <p>
     *
     * <pre>
     * TYPE_NAME          VARCHAR   the canonical name used DDL statements.
     * DATA_TYPE          SMALLINT  data type code from DITypes
     * PRECISION          INTEGER   max column size.
     *                              number => max. precision.
     *                              character => max characters.
     *                              datetime => max chars, incl. fract. component.
     * LITERAL_PREFIX     VARCHAR   char(s) prefixing literal of this type;
     * LITERAL_SUFFIX     VARCHAR   char(s) terminating literal of this type;
     * CREATE_PARAMS      VARCHAR   Localized syntax order list of domain parameter keywords.
     * NULLABLE           SMALLINT  { No Nulls | Nullable | Unknown }
     * CASE_SENSITIVE     BIT       case-sensitive in collations and comparisons?
     * SEARCHABLE         SMALLINT  { None | Char (Only WHERE .. LIKE) | Basic (Except WHERE .. LIKE) | Searchable (All forms) }
     * UNSIGNED_ATTRIBUTE BIT       { TRUE  (unsigned) | FALSE (signed) | NULL (non-numeric or not applicable) }
     * FIXED_PREC_SCALE   BIT       { TRUE (fixed) | FALSE (variable) | NULL (non-numeric or not applicable) }
     * AUTO_INCREMENT     BIT       if TRUE, then automatic unique inserted when no value or NULL specified
     * LOCAL_TYPE_NAME    VARCHAR   Localized name of data type; NULL => not supported.
     * MINIMUM_SCALE      SMALLINT  minimum scale supported
     * MAXIMUM_SCALE      SMALLINT  maximum scale supported
     * SQL_DATA_TYPE      INTEGER   value of SQL CLI SQL_DESC_TYPE field in the SQLDA
     * SQL_DATETIME_SUB   INTEGER   SQL datetime/interval subcode
     * NUM_PREC_RADIX     INTEGER   base w.r.t # of digits reported in PRECISION column
     * INTERVAL_PRECISION INTEGER   interval leading precision
     * AS_TAB_COL         BIT       type supported as table column?
     * AS_PROC_COL        BIT       type supported as procedure param or return value?
     * MAX_PREC_ACT       BIGINT    like PRECISION unless value would be truncated using INTEGER
     * MIN_SCALE_ACT      INTEGER   like MINIMUM_SCALE unless value would be truncated using SMALLINT
     * MAX_SCALE_ACT      INTEGER   like MAXIMUM_SCALE unless value would be truncated using SMALLINT
     * COL_ST_CLS_NAME    VARCHAR   Java class FQN of in-memory representation
     * COL_ST_IS_SUP      BIT       Is COL_ST_CLS_NAME supported under the hosting JVM and engine build option?
     * STD_MAP_CLS_NAME   VARCHAR   Java class FQN of standard JDBC mapping
     * STD_MAP_IS_SUP     BIT       Is STD_MAP_CLS_NAME supported under the hosting JVM?
     * CST_MAP_CLS_NAME   VARCHAR   Java class FQN of HSQLDB-provided JDBC interface representation
     * CST_MAP_IS_SUP     BIT       Is CST_MAP_CLS_NAME supported under the hosting JVM and engine build option?
     * MCOL_JDBC          INTEGER   maximum character octet length representable via JDBC interface
     * MCOL_ACT           BIGINT    like MCOL_JDBC unless value would be truncated using INTEGER
     * DEF_OR_FIXED_SCALE INTEGER   default or fixed scale of numeric types
     * REMARKS            VARCHAR   localized comment on the data type
     * TYPE_SUB           INTEGER   { 1 (standard) | 2 (identity) | 4 (ignore case) }
     * </pre>
     *
     * @return a <code>Table</code> object describing all of the
     *        standard SQL types known to this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_ALLTYPEINFO() throws SQLException {

        Table t = sysTables[SYSTEM_ALLTYPEINFO];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_ALLTYPEINFO]);

            //-------------------------------------------
            // same as SYSTEM_TYPEINFO:
            // ------------------------------------------
            addColumn(t, "TYPE_NAME", VARCHAR, false);
            addColumn(t, "DATA_TYPE", SMALLINT, false);
            addColumn(t, "PRECISION", INTEGER);
            addColumn(t, "LITERAL_PREFIX", VARCHAR);
            addColumn(t, "LITERAL_SUFFIX", VARCHAR);
            addColumn(t, "CREATE_PARAMS", VARCHAR);
            addColumn(t, "NULLABLE", SMALLINT);
            addColumn(t, "CASE_SENSITIVE", BIT);
            addColumn(t, "SEARCHABLE", SMALLINT);
            addColumn(t, "UNSIGNED_ATTRIBUTE", BIT);
            addColumn(t, "FIXED_PREC_SCALE", BIT);
            addColumn(t, "AUTO_INCREMENT", BIT);
            addColumn(t, "LOCAL_TYPE_NAME", VARCHAR);
            addColumn(t, "MINIMUM_SCALE", SMALLINT);
            addColumn(t, "MAXIMUM_SCALE", SMALLINT);
            addColumn(t, "SQL_DATA_TYPE", INTEGER);
            addColumn(t, "SQL_DATETIME_SUB", INTEGER);
            addColumn(t, "NUM_PREC_RADIX", INTEGER);

            //-------------------------------------------
            // SQL CLI / ODBC - not in JDBC spec
            // ------------------------------------------
            addColumn(t, "INTERVAL_PRECISION", INTEGER);

            //-------------------------------------------
            // extended:
            //-------------------------------------------
            // level of support
            //-------------------------------------------
            addColumn(t, "AS_TAB_COL", BIT);

            // for instance, some executable methods take Connection
            // which does not map to a supported table column type
            // but which we show as JAVA_OBJECT in SYSTEM_PROCEDURECOLUMNS
            // Also, triggers take Object[] row, which we show as ARRAY
            addColumn(t, "AS_PROC_COL", BIT);

            //-------------------------------------------
            // actual values for attributes that cannot be represented
            // within the limitations of the SQL CLI / JDBC interface
            //-------------------------------------------
            addColumn(t, "MAX_PREC_ACT", BIGINT);
            addColumn(t, "MIN_SCALE_ACT", INTEGER);
            addColumn(t, "MAX_SCALE_ACT", INTEGER);

            //-------------------------------------------
            // how do we store this internally as a column value?
            //-------------------------------------------
            addColumn(t, "COL_ST_CLS_NAME", VARCHAR);
            addColumn(t, "COL_ST_IS_SUP", BIT);

            //-------------------------------------------
            // what is the standard Java mapping for the type?
            //-------------------------------------------
            addColumn(t, "STD_MAP_CLS_NAME", VARCHAR);
            addColumn(t, "STD_MAP_IS_SUP", BIT);

            //-------------------------------------------
            // what, if any, custom mapping do we provide?
            // (under the current build options and hosting VM)
            //-------------------------------------------
            addColumn(t, "CST_MAP_CLS_NAME", VARCHAR);
            addColumn(t, "CST_MAP_IS_SUP", BIT);

            //-------------------------------------------
            // what is the max representable and actual
            // character octet length, if applicable?
            //-------------------------------------------
            addColumn(t, "MCOL_JDBC", INTEGER);
            addColumn(t, "MCOL_ACT", BIGINT);

            //-------------------------------------------
            // what is the default or fixed scale, if applicable?
            //-------------------------------------------
            addColumn(t, "DEF_OR_FIXED_SCALE", INTEGER);

            //-------------------------------------------
            // Any type-specific remarks can go here
            //-------------------------------------------
            addColumn(t, "REMARKS", VARCHAR);

            //-------------------------------------------
            // required for JDBC sort contract:
            //-------------------------------------------
            addColumn(t, "TYPE_SUB", INTEGER);

            // order:  DATA_TYPE, TYPE_SUB
            t.createPrimaryKey(null, new int[] {
                1, 34
            }, true);

            return t;
        }

        Object[]   row;
        int        type;
        DITypeInfo ti;

        //-----------------------------------------
        // Same as SYSTEM_TYPEINFO
        //-----------------------------------------
        final int itype_name          = 0;
        final int idata_type          = 1;
        final int iprecision          = 2;
        final int iliteral_prefix     = 3;
        final int iliteral_suffix     = 4;
        final int icreate_params      = 5;
        final int inullable           = 6;
        final int icase_sensitive     = 7;
        final int isearchable         = 8;
        final int iunsigned_attribute = 9;
        final int ifixed_prec_scale   = 10;
        final int iauto_increment     = 11;
        final int ilocal_type_name    = 12;
        final int iminimum_scale      = 13;
        final int imaximum_scale      = 14;
        final int isql_data_type      = 15;
        final int isql_datetime_sub   = 16;
        final int inum_prec_radix     = 17;

        //------------------------------------------
        // Extentions
        //------------------------------------------
        // not in JDBC, but in SQL CLI SQLDA / ODBC
        //------------------------------------------
        final int iinterval_precision = 18;

        //------------------------------------------
        // HSQLDB-specific:
        //------------------------------------------
        final int iis_sup_as_tcol = 19;
        final int iis_sup_as_pcol = 20;

        //------------------------------------------
        final int imax_prec_or_len_act = 21;
        final int imin_scale_actual    = 22;
        final int imax_scale_actual    = 23;

        //------------------------------------------
        final int ics_cls_name         = 24;
        final int ics_cls_is_supported = 25;

        //------------------------------------------
        final int ism_cls_name         = 26;
        final int ism_cls_is_supported = 27;

        //------------------------------------------
        final int icm_cls_name         = 28;
        final int icm_cls_is_supported = 29;

        //------------------------------------------
        final int imax_char_oct_len_jdbc = 30;
        final int imax_char_oct_len_act  = 31;

        //------------------------------------------
        final int idef_or_fixed_scale = 32;

        //------------------------------------------
        final int iremarks = 33;

        //------------------------------------------
        final int itype_sub = 34;

        ti = new DITypeInfo();

        for (int i = 0; i < ALL_TYPES.length; i++) {
            ti.setTypeCode(ALL_TYPES[i][0]);
            ti.setTypeSub(ALL_TYPES[i][1]);

            row                      = t.getNewRow();
            row[itype_name]          = ti.getTypeName();
            row[idata_type]          = ti.getDataType();
            row[iprecision]          = ti.getPrecision();
            row[iliteral_prefix]     = ti.getLiteralPrefix();
            row[iliteral_suffix]     = ti.getLiteralSuffix();
            row[icreate_params]      = ti.getCreateParams();
            row[inullable]           = ti.getNullability();
            row[icase_sensitive]     = ti.isCaseSensitive();
            row[isearchable]         = ti.getSearchability();
            row[iunsigned_attribute] = ti.isUnsignedAttribute();
            row[ifixed_prec_scale]   = ti.isFixedPrecisionScale();
            row[iauto_increment]     = ti.isAutoIncrement();
            row[ilocal_type_name]    = ti.getLocalName();
            row[iminimum_scale]      = ti.getMinScale();
            row[imaximum_scale]      = ti.getMaxScale();
            row[isql_data_type]      = ti.getSqlDataType();
            row[isql_datetime_sub]   = ti.getSqlDateTimeSub();
            row[inum_prec_radix]     = ti.getNumPrecRadix();

            //------------------------------------------
            row[iinterval_precision] = ti.getIntervalPrecision();

            //------------------------------------------
            row[iis_sup_as_tcol] = ti.isSupportedAsTCol();
            row[iis_sup_as_pcol] = ti.isSupportedAsPCol();

            //------------------------------------------
            row[imax_prec_or_len_act] = ti.getPrecisionAct();
            row[imin_scale_actual]    = ti.getMinScaleAct();
            row[imax_scale_actual]    = ti.getMaxScaleAct();

            //------------------------------------------
            row[ics_cls_name]         = ti.getColStClsName();
            row[ics_cls_is_supported] = ti.isColStClsSupported();

            //------------------------------------------
            row[ism_cls_name]         = ti.getStdMapClsName();
            row[ism_cls_is_supported] = ti.isStdMapClsSupported();

            //------------------------------------------
            row[icm_cls_name] = ti.getCstMapClsName();

            try {
                ns.classForName((String) row[icm_cls_name]);

                row[icm_cls_is_supported] = Boolean.TRUE;
            } catch (Exception e) {
                row[icm_cls_is_supported] = Boolean.FALSE;
            }

            //------------------------------------------
            row[imax_char_oct_len_jdbc] = ti.getCharOctLen();
            row[imax_char_oct_len_act]  = ti.getCharOctLenAct();

            //------------------------------------------
            row[idef_or_fixed_scale] = ti.getDefaultScale();

            //------------------------------------------
            row[iremarks] = ti.getRemarks();

            //------------------------------------------
            row[itype_sub] = ti.getDataTypeSub();

            t.insert(row, session);
        }

        t.setDataReadOnly(true);

        return t;
    }

    /**
     * Retrieves a <code>Table</code> object describing the
     * visible <code>Users</code> defined within this database.
     * @return table containing information about the users defined within
     *      this database
     * @throws SQLException if an error occurs while producing the table
     */
    Table SYSTEM_USERS() throws SQLException {

        Table t = sysTables[SYSTEM_USERS];

        if (t == null) {
            t = createBlankTable(sysTableHsqlNames[SYSTEM_USERS]);

            addColumn(t, "USER", VARCHAR, false);
            addColumn(t, "ADMIN", BIT, false);

            // order: USER
            // true PK
            t.createPrimaryKey(null, new int[]{ 0 }, true);

            return t;
        }

        // Intermediate holders
        HsqlArrayList users;
        User          user;
        int           userCount;
        Object[]      row;

        // Initialization
        users = database.getUserManager().listVisibleUsers(session, false);

        // Do it.
        for (int i = 0; i < users.size(); i++) {
            row    = t.getNewRow();
            user   = (User) users.get(i);
            row[0] = user.getName();
            row[1] = ValuePool.getBoolean(user.isAdmin());

            t.insert(row, null);
        }

        t.setDataReadOnly(true);

        return t;
    }
}
