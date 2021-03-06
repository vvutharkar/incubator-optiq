/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hydromatic.optiq.test;

import net.hydromatic.linq4j.Enumerator;
import net.hydromatic.linq4j.Linq4j;
import net.hydromatic.linq4j.QueryProvider;
import net.hydromatic.linq4j.Queryable;
import net.hydromatic.linq4j.expressions.Expression;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.impl.*;
import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import net.hydromatic.optiq.jdbc.OptiqConnection;

import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeFactory;

import org.junit.Ignore;
import org.junit.Test;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static net.hydromatic.optiq.test.OptiqAssert.that;

/**
 * Tests for a JDBC front-end (with some quite complex SQL) and Linq4j back-end
 * (based on in-memory collections).
 */
public class JdbcFrontLinqBackTest {
  /**
   * Runs a simple query that reads from a table in an in-memory schema.
   */
  @Test public void testSelect() {
    that()
        .query(
            "select *\n"
            + "from \"foodmart\".\"sales_fact_1997\" as s\n"
            + "where s.\"cust_id\" = 100")
        .returns(
            "cust_id=100; prod_id=10\n");
  }

  /**
   * Runs a simple query that joins between two in-memory schemas.
   */
  @Test public void testJoin() {
    that()
        .query(
            "select *\n"
            + "from \"foodmart\".\"sales_fact_1997\" as s\n"
            + "join \"hr\".\"emps\" as e\n"
            + "on e.\"empid\" = s.\"cust_id\"")
        .returns(
            "cust_id=100; prod_id=10; empid=100; deptno=10; name=Bill; salary=10000.0; commission=1000\n"
            + "cust_id=150; prod_id=20; empid=150; deptno=10; name=Sebastian; salary=7000.0; commission=null\n");
  }

  /**
   * Simple GROUP BY.
   */
  @Test public void testGroupBy() {
    that()
        .query(
            "select \"deptno\", sum(\"empid\") as s, count(*) as c\n"
            + "from \"hr\".\"emps\" as e\n"
            + "group by \"deptno\"")
        .returns(
            "deptno=20; S=200; C=1\n"
            + "deptno=10; S=360; C=3\n");
  }

  /**
   * Simple ORDER BY.
   */
  @Test public void testOrderBy() {
    that()
        .query(
            "select upper(\"name\") as un, \"deptno\"\n"
            + "from \"hr\".\"emps\" as e\n"
            + "order by \"deptno\", \"name\" desc")
        .returns(
            "UN=THEODORE; deptno=10\n"
            + "UN=SEBASTIAN; deptno=10\n"
            + "UN=BILL; deptno=10\n"
            + "UN=ERIC; deptno=20\n");
  }

  /**
   * Simple UNION, plus ORDER BY.
   *
   * <p>Also tests a query that returns a single column. We optimize this case
   * internally, using non-array representations for rows.</p>
   */
  @Test public void testUnionAllOrderBy() {
    that()
        .query(
            "select \"name\"\n"
            + "from \"hr\".\"emps\" as e\n"
            + "union all\n"
            + "select \"name\"\n"
            + "from \"hr\".\"depts\"\n"
            + "order by 1 desc")
        .returns(
            "name=Theodore\n"
            + "name=Sebastian\n"
            + "name=Sales\n"
            + "name=Marketing\n"
            + "name=HR\n"
            + "name=Eric\n"
            + "name=Bill\n");
  }

  /**
   * Tests UNION.
   */
  @Test public void testUnion() {
    that()
        .query(
            "select substring(\"name\" from 1 for 1) as x\n"
            + "from \"hr\".\"emps\" as e\n"
            + "union\n"
            + "select substring(\"name\" from 1 for 1) as y\n"
            + "from \"hr\".\"depts\"")
        .returnsUnordered(
            "X=T",
            "X=E",
            "X=S",
            "X=B",
            "X=M",
            "X=H");
  }

  /**
   * Tests INTERSECT.
   */
  @Ignore
  @Test public void testIntersect() {
    that()
        .query(
            "select substring(\"name\" from 1 for 1) as x\n"
            + "from \"hr\".\"emps\" as e\n"
            + "intersect\n"
            + "select substring(\"name\" from 1 for 1) as y\n"
            + "from \"hr\".\"depts\"")
        .returns(
            "X=S\n");
  }

  /**
   * Tests EXCEPT.
   */
  @Ignore
  @Test public void testExcept() {
    that()
        .query(
            "select substring(\"name\" from 1 for 1) as x\n"
            + "from \"hr\".\"emps\" as e\n"
            + "except\n"
            + "select substring(\"name\" from 1 for 1) as y\n"
            + "from \"hr\".\"depts\"")
        .returnsUnordered(
            "X=T",
            "X=E",
            "X=B");
  }

  @Test public void testWhereBad() {
    that()
        .query(
            "select *\n"
            + "from \"foodmart\".\"sales_fact_1997\" as s\n"
            + "where empid > 120")
        .throws_("Column 'EMPID' not found in any table");
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/OPTIQ-9">OPTIQ-9</a>,
   * "RexToLixTranslator not incrementing local variable name counter". */
  @Test public void testWhereOr() {
    that()
        .query(
            "select * from \"hr\".\"emps\"\n"
            + "where (\"empid\" = 100 or \"empid\" = 200)\n"
            + "and \"deptno\" = 10")
        .returns(
            "empid=100; deptno=10; name=Bill; salary=10000.0; commission=1000\n");
  }

  @Test public void testWhereLike() {
    that()
        .query(
            "select *\n"
            + "from \"hr\".\"emps\" as e\n"
            + "where e.\"empid\" < 120 or e.\"name\" like 'S%'")
        .returns(
            "empid=100; deptno=10; name=Bill; salary=10000.0; commission=1000\n"
            + "empid=150; deptno=10; name=Sebastian; salary=7000.0; commission=null\n"
            + "empid=110; deptno=10; name=Theodore; salary=11500.0; commission=250\n");
  }

  @Test public void testInsert() {
    final List<JdbcTest.Employee> employees =
        new ArrayList<JdbcTest.Employee>();
    OptiqAssert.AssertThat with = mutable(employees);
    with.query("select * from \"foo\".\"bar\"")
        .returns(
            "empid=0; deptno=0; name=first; salary=0.0; commission=null\n");
    with.query("insert into \"foo\".\"bar\" select * from \"hr\".\"emps\"")
        .returns("ROWCOUNT=4\n");
    with.query("select count(*) as c from \"foo\".\"bar\"")
        .returns("C=5\n");
    with.query(
        "insert into \"foo\".\"bar\" "
        + "select * from \"hr\".\"emps\" where \"deptno\" = 10")
        .returns("ROWCOUNT=3\n");
    with.query(
        "select \"name\", count(*) as c from \"foo\".\"bar\" "
        + "group by \"name\"")
        .returnsUnordered(
            "name=Bill; C=2",
            "name=Eric; C=1",
            "name=Theodore; C=2",
            "name=first; C=1",
            "name=Sebastian; C=2");
  }

  private OptiqAssert.AssertThat mutable(
      final List<JdbcTest.Employee> employees) {
    employees.add(new JdbcTest.Employee(0, 0, "first", 0f, null));
    return that()
        .with(
            new OptiqAssert.ConnectionFactory() {
              public OptiqConnection createConnection() throws Exception {
                final Connection connection =
                    OptiqAssert.getConnection("hr", "foodmart");
                OptiqConnection optiqConnection = connection.unwrap(
                    OptiqConnection.class);
                SchemaPlus rootSchema =
                    optiqConnection.getRootSchema();
                SchemaPlus mapSchema =
                    rootSchema.add("foo", new AbstractSchema());
                final String tableName = "bar";
                final JdbcTest.AbstractModifiableTable table =
                    new JdbcTest.AbstractModifiableTable(tableName) {
                      public RelDataType getRowType(
                          RelDataTypeFactory typeFactory) {
                        return ((JavaTypeFactory) typeFactory)
                            .createType(JdbcTest.Employee.class);
                      }

                      public <T> Queryable<T> asQueryable(
                          QueryProvider queryProvider, SchemaPlus schema,
                          String tableName) {
                        return new AbstractTableQueryable<T>(queryProvider,
                            schema, this, tableName) {
                          public Enumerator<T> enumerator() {
                            //noinspection unchecked
                            return (Enumerator<T>) Linq4j.enumerator(employees);
                          }
                        };
                      }

                      public Type getElementType() {
                        return JdbcTest.Employee.class;
                      }

                      public Expression getExpression(SchemaPlus schema,
                          String tableName, Class clazz) {
                        return Schemas.tableExpression(schema, getElementType(),
                            tableName, clazz);
                      }

                      public Collection getModifiableCollection() {
                        return employees;
                      }
                    };
                mapSchema.add(tableName, table);
                return optiqConnection;
              }
            });
  }

  @Test public void testInsert2() {
    final List<JdbcTest.Employee> employees =
        new ArrayList<JdbcTest.Employee>();
    OptiqAssert.AssertThat with = mutable(employees);
    with.query("insert into \"foo\".\"bar\" values (1, 1, 'second', 2, 2)")
        .returns("ROWCOUNT=1\n");
    with.query(
        "insert into \"foo\".\"bar\"\n"
        + "values (1, 3, 'third', 0, 3), (1, 4, 'fourth', 0, 4), (1, 5, 'fifth ', 0, 3)")
        .returns("ROWCOUNT=3\n");
    with.query("select count(*) as c from \"foo\".\"bar\"")
        .returns("C=5\n");
    with.query("insert into \"foo\".\"bar\" values (1, 6, null, 0, null)")
        .returns("ROWCOUNT=1\n");
    with.query("select count(*) as c from \"foo\".\"bar\"")
        .returns("C=6\n");
  }

  /** Some of the rows have the wrong number of columns. */
  @Test public void testInsertMultipleRowMismatch() {
    final List<JdbcTest.Employee> employees =
        new ArrayList<JdbcTest.Employee>();
    OptiqAssert.AssertThat with = mutable(employees);
    with.query(
        "insert into \"foo\".\"bar\" values\n"
        + " (1, 3, 'third'),\n"
        + " (1, 4, 'fourth'),\n"
        + " (1, 5, 'fifth ', 3)")
        .throws_("Incompatible types");
  }
}

// End JdbcFrontLinqBackTest.java
