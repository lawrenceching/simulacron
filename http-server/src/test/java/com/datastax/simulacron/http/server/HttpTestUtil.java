package com.datastax.simulacron.http.server;

import com.datastax.driver.core.*;
import com.datastax.driver.core.Statement;
import com.datastax.simulacron.common.cluster.Cluster;
import com.datastax.simulacron.common.cluster.Node;
import com.datastax.simulacron.common.cluster.RequestPrime;
import com.datastax.simulacron.common.request.*;
import com.datastax.simulacron.common.result.Result;
import com.datastax.simulacron.common.result.SuccessResult;

import java.lang.reflect.Array;
import java.util.*;

import static com.datastax.simulacron.driver.SimulacronDriverSupport.defaultBuilder;

public class HttpTestUtil {

  public static ResultSet makeNativeQuery(String query, String contactPoint) {

    SimpleStatement statement = new SimpleStatement(query);
    return executeQueryWithFreshSession(statement, contactPoint);
  }

  public static ResultSet makeNativeQueryWithNameParams(
      String query, String contactPoint, Map<String, Object> values) {
    SimpleStatement statement = new SimpleStatement(query, values);
    return executeQueryWithFreshSession(statement, contactPoint);
  }

  public static ResultSet makeNativeBoundQueryWithNameParams(
      String query, String contactPoint, Map<String, Long> values) {
    com.datastax.driver.core.Cluster cluster =
        defaultBuilder().addContactPoint(contactPoint).build();
    Session session = cluster.connect();
    com.datastax.driver.core.PreparedStatement prepared = session.prepare(query);
    BoundStatement bound = prepared.bind();
    values.forEach((k, v) -> bound.setLong(k, v));
    return executeQueryWithFreshSession(bound, contactPoint, session, cluster);
  }

  public static ResultSet makeNativeBoundQueryWithNameParamsStrings(
      String query, String contactPoint, Map<String, String> values) {
    com.datastax.driver.core.Cluster cluster =
        defaultBuilder().addContactPoint(contactPoint).build();
    Session session = cluster.connect();
    com.datastax.driver.core.PreparedStatement prepared = session.prepare(query);
    BoundStatement bound = getBoundStatementNamed(query, contactPoint, values);
    return executeQueryWithFreshSession(bound, contactPoint, session, cluster);
  }

  public static ResultSet makeNativeQueryWithPositionalParams(
      String query, String contactPoint, Object... values) {
    SimpleStatement statement = new SimpleStatement(query, values);
    return executeQueryWithFreshSession(statement, contactPoint);
  }

  public static ResultSet makeNativeBoundQueryWithPositionalParam(
      String query, String contactPoint, Object param) {
    com.datastax.driver.core.Cluster cluster =
        defaultBuilder().addContactPoint(contactPoint).build();
    Session session = cluster.connect();
    com.datastax.driver.core.PreparedStatement prepared = session.prepare(query);
    BoundStatement bound = getBoundStatement(query, contactPoint, param);
    return executeQueryWithFreshSession(bound, contactPoint, session, cluster);
  }

  public static BoundStatement getBoundStatement(String query, String contactPoint, Object param) {
    com.datastax.driver.core.Cluster cluster =
        defaultBuilder().addContactPoint(contactPoint).build();
    Session session = cluster.connect();
    com.datastax.driver.core.PreparedStatement prepared = session.prepare(query);
    BoundStatement bound = prepared.bind(param);
    cluster.close();
    return bound;
  }

  public static BoundStatement getBoundStatementNamed(
      String query, String contactPoint, Map<String, String> values) {
    com.datastax.driver.core.Cluster cluster =
        defaultBuilder().addContactPoint(contactPoint).build();
    Session session = cluster.connect();
    com.datastax.driver.core.PreparedStatement prepared = session.prepare(query);
    BoundStatement bound = prepared.bind();
    values.forEach((k, v) -> bound.setString(k, v));
    cluster.close();
    return bound;
  }

  public static BatchStatement makeNativeBatchStatement(List<String> queries, List<List> values) {

    BatchStatement statement = new BatchStatement();
    Iterator<List> valuesIterator = values.iterator();
    for (String query : queries) {
      List value = valuesIterator.next();
      statement.add(new SimpleStatement(query, value.toArray(new Object[value.size()])));
    }
    return statement;
  }

  public static RequestPrime createSimplePrimedQuery(String query) {
    return createSimpleParameterizedQuery(query, null, null);
  }

  public static RequestPrime createSimpleParameterizedQuery(
      String query, HashMap<String, Object> params, HashMap<String, String> paramTypes) {
    Query when = new Query(query, Collections.emptyList(), params, paramTypes);
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    HashMap row1 = new HashMap<String, String>();
    row1.put("column1", "column1");
    row1.put("column2", "2");
    rows.add(row1);
    Map<String, String> column_types = new HashMap<String, String>();
    column_types.put("column1", "ascii");
    column_types.put("column2", "bigint");
    Result then = new SuccessResult(rows, column_types);
    RequestPrime requestPrime = new RequestPrime(when, then);
    return requestPrime;
  }

  public static RequestPrime createParameterizedBatch(
      List<String> queries,
      List<Map<String, String>> paramTypes,
      List<Map<String, Object>> params) {
    Iterator<String> queryIterator = queries.iterator();
    Iterator<Map<String, String>> paramTypesIterator = paramTypes.iterator();
    Iterator<Map<String, Object>> paramsIterator = params.iterator();

    List<com.datastax.simulacron.common.request.Statement> statements = new ArrayList<>();

    while (queryIterator.hasNext()) {
      statements.add(
          new com.datastax.simulacron.common.request.Statement(
              queryIterator.next(), paramTypesIterator.next(), paramsIterator.next()));
    }

    Batch when = new Batch(statements, Collections.emptyList());

    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    HashMap row1 = new HashMap<String, String>();
    row1.put("applied", "true");
    rows.add(row1);

    Map<String, String> column_types_result = new HashMap<String, String>();
    column_types_result.put("applied", "boolean");
    Result then = new SuccessResult(rows, column_types_result);
    return new RequestPrime(when, then);
  }

  public static RequestPrime createSimpleParameterizedBatch(
      String query, Map<String, String> paramTypes, Map<String, Object> params) {
    return createParameterizedBatch(
        Arrays.asList(query), Arrays.asList(paramTypes), Arrays.asList(params));
  }

  public static String getContactPointStringByNodeID(Node node) {
    String rawaddress = node.getAddress().toString();
    return rawaddress.substring(1, rawaddress.length() - 5);
  }

  public static String getContactPointString(Cluster cluster, int node) {
    String rawaddress = cluster.getNodes().get(node).getAddress().toString();
    return rawaddress.substring(1, rawaddress.length() - 5);
  }

  public static String getContactPointString(Cluster cluster) {
    return getContactPointString(cluster, 0);
  }

  public static ResultSet executeQueryWithFreshSession(Statement statement, String contactPoint) {
    return executeQueryWithFreshSession(statement, contactPoint, null, null);
  }

  private static ResultSet executeQueryWithFreshSession(
      Statement statement,
      String contactPoint,
      Session session,
      com.datastax.driver.core.Cluster cluster) {
    if (session == null) {
      cluster = defaultBuilder().addContactPoint(contactPoint).build();
      session = cluster.connect();
    }
    ResultSet rs = session.execute(statement);
    cluster.close();
    ;
    return rs;
  }
}