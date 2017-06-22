package com.datastax.simulacron.http.server;

import com.datastax.oss.protocol.internal.response.result.RawType;
import com.datastax.simulacron.common.cluster.ObjectMapperHolder;
import com.datastax.simulacron.common.cluster.RequestPrime;
import com.datastax.simulacron.common.cluster.Scope;
import com.datastax.simulacron.common.codec.CodecUtils;
import com.datastax.simulacron.common.result.SuccessResult;
import com.datastax.simulacron.common.stubbing.Prime;
import com.datastax.simulacron.server.Server;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.datastax.simulacron.http.server.HttpUtils.handleMessage;

public class QueryManager implements HttpListener {
  Logger logger = LoggerFactory.getLogger(QueryManager.class);
  Server server;

  public QueryManager(Server server) {
    this.server = server;
  }

  /**
   * This is an async callback that will be invoked whenever a request to /prime is posted. It will
   * parse the provided json and construct a query which when called from the server will return the
   * rows defined.
   *
   * <p>Example Supported HTTP Requests
   *
   * <p>POST http://iphere:porthere/prime-query-single with json body of {@code { "when": { "query":
   * "SELECT * FROM table" }, "then": { "rows": [ { "row1": "sample1", "row2": "1" } ], "result":
   * "success", "column_types": { "row1": "ascii", "row2": "bigint" } } }}
   *
   * <p>This will return a row containing two columns when a SELECT * FROM table. The row will
   * contain two fields row1, and row2, that are of type ascii and bigint respectively
   *
   * @param context RoutingContext provided by vertx
   */
  public void primeQuery(RoutingContext context) {

    context
        .request()
        .bodyHandler(
            totalBuffer -> {
              String jsonBody = "";
              try {
                logger.info("Full body received, length = " + totalBuffer.length());

                Scope scope = HttpUtils.getScope(context, server);
                if (scope == null) {
                  return;
                }

                jsonBody = totalBuffer.toString();
                ObjectMapper om = ObjectMapperHolder.getMapper();
                RequestPrime query = om.readValue(jsonBody, RequestPrime.class);

                if (query.then instanceof SuccessResult) {
                  SuccessResult success = (SuccessResult) query.then;
                  for (String key : success.columnTypes.keySet()) {
                    String typeName = success.columnTypes.get(key);
                    RawType type = CodecUtils.getTypeFromName(typeName);
                    if (type == null) {
                      handleBadType(key, typeName, context);
                    }
                  }
                }
                Prime prime = new Prime(query, scope);

                server.prime(prime);
              } catch (Exception e) {
                handleQueryError(e, "prime query", context);
              }
              if (!context.response().ended()) {
                context
                    .request()
                    .response()
                    .putHeader("content-type", "application/json")
                    .setStatusCode(201)
                    .end(jsonBody);
              }
            });
  }
  /**
   * This is an async callback that will be invoked whenever a request to /clear_primed is posted.
   * It will remove all primed queries that have been set so far
   *
   * <p>Example Supported HTTP Requests
   *
   * <p>POST http://iphere:porthere/clear_primed no payload.
   *
   * @param context RoutingContext provided by vertx
   */
  public void clearPrimedQueries(RoutingContext context) {
    context
        .request()
        .bodyHandler(
            totalBuffer -> {
              String jsonBody = "";

              Scope scope = HttpUtils.getScope(context, server);
              if (scope == null) {
                return;
              }

              int cleared = 0;
              try {
                server.clear(scope, Prime.class);
              } catch (Exception e) {
                handleQueryError(e, "clear primed queries", context);
              }
              if (!context.response().ended()) {
                handleMessage(new Message("Cleared " + cleared + " primed queries", 202), context);
              }
            });
  }

  /**
   * Convenience method to set failure on response and print a relative error messaged
   *
   * @param e Exception thrown
   * @param operation for logging.
   * @param context RoutingContext to set failure upon
   */
  private void handleQueryError(Throwable e, String operation, RoutingContext context) {
    String errorString =
        "Error encountered while attempting to " + operation + ". See logs for details";
    HttpUtils.handleError(new ErrorMessage(errorString, 400, e), context);
  }
  /**
   * Convenience method to set failure on response when an invalid type is detected
   *
   * @param context RoutingContext to set failure upon
   */
  private void handleBadType(String key, String typeName, RoutingContext context) {
    String errorMsg =
        "Invalid type defined for column " + key + ", " + typeName + " is not a recognized type.";
    HttpUtils.handleError(new ErrorMessage(errorMsg, 400), context);
  }

  public void registerWithRouter(Router router) {
    // Priming queries
    router.route(HttpMethod.POST, "/prime-query-single/:clusterIdOrName").handler(this::primeQuery);
    router
        .route(HttpMethod.POST, "/prime-query-single/:clusterIdOrName/:datacenterIdOrName")
        .handler(this::primeQuery);
    router
        .route(
            HttpMethod.POST,
            "/prime-query-single/:clusterIdOrName/:datacenterIdOrName/:nodeIdOrName")
        .handler(this::primeQuery);

    router.route(HttpMethod.POST, "/prime*").handler(this::primeQuery);

    //Deleting primed queries
    router
        .route(HttpMethod.DELETE, "/prime-query-single/:clusterIdOrName")
        .handler(this::clearPrimedQueries);
    router
        .route(HttpMethod.DELETE, "/prime-query-single/:clusterIdOrName/:datacenterIdOrName")
        .handler(this::clearPrimedQueries);
    router
        .route(
            HttpMethod.DELETE,
            "/prime-query-single/:clusterIdOrName/:datacenterIdOrName/:nodeIdOrName")
        .handler(this::primeQuery);
    router.route(HttpMethod.DELETE, "/prime*").handler(this::clearPrimedQueries);
  }
}
