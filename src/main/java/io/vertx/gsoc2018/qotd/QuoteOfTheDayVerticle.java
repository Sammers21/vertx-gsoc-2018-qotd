package io.vertx.gsoc2018.qotd;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;

/**
 * @author Thomas Segismont
 */
public class QuoteOfTheDayVerticle extends AbstractVerticle {

  private static final Logger logger = LoggerFactory.getLogger(QuoteOfTheDayVerticle.class.getName());

  private JDBCClient jdbcClient;
  private static final String DB_UPDATES_ADDRESS = "db.updates";
  private static final String QUOTES_PATH = "/quotes";
  private static final String DEFAULT_AUTHOR_VALUE = "Unknown";
  private static final String AUTHOR_FILED = "author";
  private static final String TEXT_FILED = "text";

  @Override
  public void start(Future<Void> startFuture) {
    Future<Void> dbReady = Future.future();
    Future<Void> httpServerReady = Future.future();

    // complete startFuture only when db and http server started
    compose(startFuture, dbReady, httpServerReady);
    compose(startFuture, httpServerReady, dbReady);

    JsonObject jdbcConfig = new JsonObject()
        .put("url", "jdbc:h2:mem:test;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
        .put("driver_class", "org.h2.Driver");

    jdbcClient = JDBCClient.createShared(vertx, jdbcConfig);

    // web server setup
    HttpServer server = vertx.createHttpServer();
    Router router = Router.router(vertx);

    router.post(QUOTES_PATH).handler(routingContext -> {
      logger.info("receive post");
      HttpServerResponse response = routingContext.response();
      routingContext.request().bodyHandler(requestBody -> {
        JsonObject request = requestBody.toJsonObject();
        String text = request.getString(TEXT_FILED);
        if (text == null) {
          response.setStatusCode(404);
          response.end("json in a POST request should have a 'text' filed");
          return;
        }

        String author = request.getString(AUTHOR_FILED);
        if (author == null) {
          author = DEFAULT_AUTHOR_VALUE;
        }

        String finalAuthor = author;
        jdbcClient.getConnection(getConn -> {
          if (getConn.succeeded()) {
            SQLConnection connection = getConn.result();
            String sqlToExecute = "INSERT INTO quotes (text,author) VALUES (?, ?);";
            JsonArray sqlParams = new JsonArray().add(text).add(finalAuthor);
            connection.updateWithParams(sqlToExecute, sqlParams, exec -> {
              if (exec.succeeded()) {
                response.setStatusCode(200);
                response.end();

                // notify subscribers about database update
                JsonObject newQuote = new JsonObject()
                  .put(AUTHOR_FILED, finalAuthor)
                  .put(TEXT_FILED, text);
                vertx.eventBus().publish(DB_UPDATES_ADDRESS, newQuote);
              } else {
                logger.error("failed in query executing", exec.cause());
                responseWithError(response);
              }
              connection.close();
            });
          } else {
            logger.error("failed in db connection establishing", getConn.cause());
            responseWithError(response);
          }
        });

      });
    });

    router.get(QUOTES_PATH).handler(routingContext -> {
      HttpServerResponse response = routingContext.response();
      jdbcClient.getConnection(getConn -> {
        if (getConn.succeeded()) {
          SQLConnection connection = getConn.result();
          String sqlToExecute = "SELECT * FROM quotes";
          connection.query(sqlToExecute, query -> {
            if (query.succeeded()) {
              JsonArray responseBody = new JsonArray();
              query.result().getRows().forEach(responseBody::add);
              response.end(responseBody.toBuffer());
            } else {
              logger.error("failed in query executing", query.cause());
              responseWithError(response);
            }
            connection.close();
          });
        } else {
          logger.error("failed in db connection establishing", getConn.cause());
          responseWithError(response);
        }
      });
    });

    server.websocketHandler(ws -> {
      if (ws.path().equals("/realtime")) {
        ws.accept();
        MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(DB_UPDATES_ADDRESS, message -> {
          ws.writeTextMessage(message.body().toString());
        });
        ws.closeHandler(v -> consumer.unregister());
      } else {
        ws.reject();
      }
    });


    int port = config().getInteger("http.port", 8080);
    server.requestHandler(router::accept).listen(port, listenHandler -> {
      if (listenHandler.succeeded()) {
        httpServerReady.complete();
        logger.info("HTTP successfully started on port " + port);
      } else {
        httpServerReady.fail(listenHandler.cause());
        logger.error("Failed to start server on port " + port, listenHandler.cause());
      }
    });

    Future<Void> initSchema = runScript("db.sql");

    initSchema.setHandler(initSchemaResult -> {
      if (initSchemaResult.succeeded()) {
        logger.info("initial schema successfully had been loaded");
        Future<Void> importData = runScript("import.sql");
        importData.setHandler(dataImportResult -> {
          if (dataImportResult.succeeded()) {
            dbReady.complete();
            logger.info("initial data had been loaded");
          } else {
            dbReady.fail(dataImportResult.cause());
            logger.error("failed in attempt to load initial data", dataImportResult.cause());
          }
        });
      } else {
        dbReady.fail(initSchema.cause());
        logger.error("failed in attempt to load initial schema", initSchemaResult.cause());
      }
    });
  }

  /**
   * resultFuture will be completed only if conditionFuture will succeed at the moment when targetFuture will be completed
   */
  private void compose(Future<Void> resultFuture, Future<Void> conditionFuture, Future<Void> targetFuture) {
    targetFuture.setHandler(result -> {
      if (result.succeeded()) {
        if (conditionFuture.succeeded()) {
          resultFuture.complete();
        }
      } else {
        resultFuture.tryFail(result.cause());
      }
    });
  }

  private void responseWithError(HttpServerResponse response) {
    response.setStatusCode(400);
    response.end("Something went wrong");
  }

  private Future<Void> runScript(String script) {
    Future<Void> future = Future.future();

    vertx.fileSystem().readFile(script, fileReadResult -> {
      if (fileReadResult.succeeded()) {
        jdbcClient.getConnection(getConn -> {
          if (getConn.succeeded()) {
            SQLConnection connection = getConn.result();
            String sqlToExecute = fileReadResult.result().toString();
            logger.info("Executing sql statement " + sqlToExecute);
            connection.execute(sqlToExecute, exec -> {
              connection.close();
              if (exec.succeeded()) {
                future.complete();
              } else {
                future.fail(exec.cause());
              }
            });
          } else {
            future.fail(getConn.cause());
          }
        });
      } else {
        future.fail(fileReadResult.cause());
      }
    });
    return future;
  }

  @Override
  public void stop() {
    jdbcClient.close();
  }
}
