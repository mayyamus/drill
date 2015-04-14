/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.drill.exec.planner.sql.handlers;

import static org.apache.drill.exec.planner.sql.parser.DrillParserUtil.CHARSET;

import java.util.List;

import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;

import org.apache.drill.exec.ops.QueryContext;
import org.apache.drill.exec.planner.sql.parser.DrillParserUtil;
import org.apache.drill.exec.planner.sql.parser.SqlDescribeTable;
import org.apache.drill.exec.store.AbstractSchema;
import org.apache.drill.exec.store.ischema.InfoSchemaConstants;
import org.apache.drill.exec.work.foreman.ForemanSetupException;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

public class DescribeTableHandler extends DefaultSqlHandler implements InfoSchemaConstants {

  public DescribeTableHandler(SqlHandlerConfig config) { super(config); }

  /** Rewrite the parse tree as SELECT ... FROM INFORMATION_SCHEMA.COLUMNS ... */
  @Override
  public SqlNode rewrite(SqlNode sqlNode) throws RelConversionException, ForemanSetupException {
    SqlDescribeTable node = unwrap(sqlNode, SqlDescribeTable.class);

    try {
      List<SqlNode> selectList = ImmutableList.of((SqlNode) new SqlIdentifier(COL_COLUMN_NAME, SqlParserPos.ZERO),
          new SqlIdentifier(COL_DATA_TYPE, SqlParserPos.ZERO),
          new SqlIdentifier(COL_IS_NULLABLE, SqlParserPos.ZERO));

      SqlNode fromClause = new SqlIdentifier(
          ImmutableList.of(IS_SCHEMA_NAME, TAB_COLUMNS), null, SqlParserPos.ZERO, null);

      final SqlIdentifier table = node.getTable();
      final SchemaPlus schema = findSchema(context.getRootSchema(), context.getNewDefaultSchema(),
          Util.skipLast(table.names));
      final String tableName = Util.last(table.names);

      if (schema.getTable(tableName) == null) {
        throw new RelConversionException(String.format("Table %s is not valid", Util.sepList(table.names, ".")));
      }

      SqlNode schemaCondition = null;
      if (!isRootSchema(schema)) {
        AbstractSchema drillSchema = getDrillSchema(schema);

        schemaCondition = DrillParserUtil.createCondition(
            new SqlIdentifier(COL_TABLE_SCHEMA, SqlParserPos.ZERO),
            SqlStdOperatorTable.EQUALS,
            SqlLiteral.createCharString(drillSchema.getFullSchemaName(), CHARSET, SqlParserPos.ZERO)
        );
      }

      SqlNode where = DrillParserUtil.createCondition(
          new SqlIdentifier(COL_TABLE_NAME, SqlParserPos.ZERO),
          SqlStdOperatorTable.EQUALS,
          SqlLiteral.createCharString(tableName, CHARSET, SqlParserPos.ZERO));

      where = DrillParserUtil.createCondition(schemaCondition, SqlStdOperatorTable.AND, where);

      SqlNode columnFilter = null;
      if (node.getColumn() != null) {
        columnFilter = DrillParserUtil.createCondition(new SqlIdentifier(COL_COLUMN_NAME, SqlParserPos.ZERO),
            SqlStdOperatorTable.EQUALS,
            SqlLiteral.createCharString(node.getColumn().toString(), CHARSET, SqlParserPos.ZERO));
      } else if (node.getColumnQualifier() != null) {
        columnFilter = DrillParserUtil.createCondition(new SqlIdentifier(COL_COLUMN_NAME, SqlParserPos.ZERO),
            SqlStdOperatorTable.LIKE, node.getColumnQualifier());
      }

      where = DrillParserUtil.createCondition(where, SqlStdOperatorTable.AND, columnFilter);

      return new SqlSelect(SqlParserPos.ZERO, null, new SqlNodeList(selectList, SqlParserPos.ZERO),
          fromClause, where, null, null, null, null, null, null);
    } catch (Exception ex) {
      throw new RelConversionException("Error while rewriting DESCRIBE query: " + ex.getMessage(), ex);
    }
  }
}