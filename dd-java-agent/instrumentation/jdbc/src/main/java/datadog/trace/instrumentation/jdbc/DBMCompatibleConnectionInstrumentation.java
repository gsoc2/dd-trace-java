package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_STATIC;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class DBMCompatibleConnectionInstrumentation extends AbstractConnectionInstrumentation
    implements Instrumenter.ForKnownTypes {

  /** Instrumentation class for connections for Database Monitoring supported DBs * */
  public DBMCompatibleConnectionInstrumentation() {
    super("jdbc", "dbm_compatible");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.Statement", DBQueryInfo.class.getName());
  }

  // Classes to cover all currently supported
  // db types for the Database Monitoring product
  static final String[] CONCRETE_TYPES = {
    "com.microsoft.sqlserver.jdbc.SQLServerConnection",
    // should cover mysql
    "com.mysql.jdbc.Connection",
    "com.mysql.jdbc.jdbc1.Connection",
    "com.mysql.jdbc.jdbc2.Connection",
    "com.mysql.jdbc.ConnectionImpl",
    "com.mysql.jdbc.JDBC4Connection",
    "com.mysql.cj.jdbc.ConnectionImpl",
    // should cover Oracle
    "oracle.jdbc.driver.PhysicalConnection",
    // complete
    "org.mariadb.jdbc.MySQLConnection",
    // MariaDB Connector/J v2.x
    "org.mariadb.jdbc.MariaDbConnection",
    // MariaDB Connector/J v3.x
    "org.mariadb.jdbc.Connection",
    // postgresql seems to be complete
    "org.postgresql.jdbc.PgConnection",
    "org.postgresql.jdbc1.Connection",
    "org.postgresql.jdbc1.Jdbc1Connection",
    "org.postgresql.jdbc2.Connection",
    "org.postgresql.jdbc2.Jdbc2Connection",
    "org.postgresql.jdbc3.Jdbc3Connection",
    "org.postgresql.jdbc3g.Jdbc3gConnection",
    "org.postgresql.jdbc4.Jdbc4Connection",
    "postgresql.Connection",
    // EDB version of postgresql
    "com.edb.jdbc.PgConnection",
    // jtds (for SQL Server and Sybase)
    "net.sourceforge.jtds.jdbc.ConnectionJDBC2", // 1.2
    "net.sourceforge.jtds.jdbc.JtdsConnection", // 1.3
    // aws-mysql-jdbc
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.jdbc.ConnectionImpl",
  };

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator", packageName + ".SQLCommenter",
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    return CONCRETE_TYPES;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a subtype of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        DBMCompatibleConnectionInstrumentation.class.getName() + "$ConnectionPrepareAdvice");
  }

  public static class ConnectionPrepareAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static String onEnter(
        @Advice.This Connection connection,
        @Advice.Argument(value = 0, readOnly = false) String sql) {
      if (JDBCDecorator.injectSQLComment()) {
        final int callDepth =
            CallDepthThreadLocalMap.incrementCallDepth(ConnectionPrepareAdvice.class);
        if (callDepth > 0) {
          return null;
        }
        final String inputSql = sql;
        final DBInfo dbInfo = JDBCDecorator.parseDBInfoFromConnection(connection);
        String dbService = DECORATE.dbService(dbInfo);
        SQLCommenter commenter = new SQLCommenter(SQL_COMMENT_INJECTION_STATIC, sql, dbService);
        commenter.inject();
        sql = commenter.getCommentedSQL();
        return inputSql;
      }
      return sql;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.Enter final String inputSql, @Advice.Return final PreparedStatement statement) {
      if (null == inputSql) {
        return;
      }
      ContextStore<Statement, DBQueryInfo> contextStore =
          InstrumentationContext.get(Statement.class, DBQueryInfo.class);
      if (null == contextStore.get(statement)) {
        DBQueryInfo info = DBQueryInfo.ofPreparedStatement(inputSql);
        contextStore.put(statement, info);
      }
      CallDepthThreadLocalMap.reset(ConnectionPrepareAdvice.class);
    }
  }
}
