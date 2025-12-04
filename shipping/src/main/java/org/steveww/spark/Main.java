package org.steveww.spark;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import spark.Spark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.MapListHandler;
import org.apache.commons.dbutils.DbUtils;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

public class Main {
    private static String CART_URL = null;
    private static String JDBC_URL = null;
    private static Logger logger = LoggerFactory.getLogger(Main.class);
    private static ComboPooledDataSource cpds = null;
    
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public static void main(String[] args) {
        // Get ENV configuration values
        CART_URL = String.format("http://%s/shipping/", System.getenv("CART_ENDPOINT") != null ? System.getenv("CART_ENDPOINT") : "cart");
        JDBC_URL = String.format("jdbc:mysql://%s/cities?useSSL=false&autoReconnect=true", System.getenv("DB_HOST") != null ? System.getenv("DB_HOST") : "mysql");

        // Create database connector with retry
        cpds = RetryUtil.withRetry(() -> {
            ComboPooledDataSource ds = new ComboPooledDataSource();
            ds.setDriverClass("com.mysql.jdbc.Driver"); //loads the jdbc driver            
            ds.setJdbcUrl(JDBC_URL);
            ds.setUser("shipping");                                  
            ds.setPassword("secret");
            // some config
            ds.setMinPoolSize(5);
            ds.setAcquireIncrement(5);
            ds.setMaxPoolSize(20);
            ds.setMaxStatements(180);
            
            // Test the connection
            ds.getConnection().close();
            return ds;
        }, MAX_RETRIES, RETRY_DELAY_MS, "Database initialization");

        // Spark
        Spark.port(8080);

        Spark.get("/health", (req, res) -> "OK");

        Spark.get("/count", (req, res) -> {
            String data;
            try {
                data = queryToJson("select count(*) as count from cities");
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("count", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        Spark.get("/codes", (req, res) -> {
            String data;
            try {
                String query = "select code, name from codes order by name asc";
                data = queryToJson(query);
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("codes", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        // needed for load gen script
        Spark.get("/cities/:code", (req, res) -> {
            String data;
            try {
                String query = "select uuid, name from cities where country_code = ?";
                logger.info("Query " + query);
                data = queryToJson(query, req.params(":code"));
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("cities", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        Spark.get("/match/:code/:text", (req, res) -> {
            String data;
            try {
                String query = "select uuid, name from cities where country_code = ? and city like ? order by name asc limit 10";
                logger.info("Query " + query);
                data = queryToJson(query, req.params(":code"), req.params(":text") + "%");
                res.header("Content-Type", "application/json");
            } catch(Exception e) {
                logger.error("match", e);
                res.status(500);
                data = "ERROR";
            }

            return data;
        });

        Spark.get("/calc/:uuid", (req, res) -> {
            double homeLat = 51.164896;
            double homeLong = 7.068792;

            res.header("Content-Type", "application/json");
            Location location = getLocation(req.params(":uuid"));
            Ship ship = new Ship();
            if(location != null) {
                long distance = location.getDistance(homeLat, homeLong);
                // charge 0.05 Euro per km
                // try to avoid rounding errors
                double cost = Math.rint(distance * 5) / 100.0;
                ship.setDistance(distance);
                ship.setCost(cost);
            } else {
                res.status(500);
            }

            return new Gson().toJson(ship);
        });

        Spark.post("/confirm/:id", (req, res) -> {
            logger.info("confirm " + req.params(":id") + " - " + req.body());
            String cart = addToCart(req.params(":id"), req.body());
            logger.info("new cart " + cart);

            if(cart.equals("")) {
                res.status(404);
            } else {
                res.header("Content-Type", "application/json");
            }

            return cart;
        });

        logger.info("Ready");
    }



    /**
     * Query to Json - QED
     **/
    private static String queryToJson(String query, Object ... args) {
        List<Map<String, Object>> listOfMaps = RetryUtil.withRetry(() -> {
            QueryRunner queryRunner = new QueryRunner(cpds);
            return queryRunner.query(query, new MapListHandler(), args);
        }, MAX_RETRIES, RETRY_DELAY_MS, "Database query");

        return new Gson().toJson(listOfMaps);
    }

    /**
     * Special case for location, dont want Json
     **/
    private static Location getLocation(String uuid) {
        String query = "select latitude, longitude from cities where uuid = " + uuid;
        
        return RetryUtil.withRetry(() -> {
            Location location = null;
            Connection conn = null;
            Statement stmt = null;
            ResultSet rs = null;
            
            try {
                conn = cpds.getConnection();
                stmt = conn.createStatement();
                rs = stmt.executeQuery(query);
                if (rs.next()) {
                    location = new Location(rs.getDouble(1), rs.getDouble(2));
                }
            } finally {
                DbUtils.closeQuietly(conn, stmt, rs);
            }
            
            return location;
        }, MAX_RETRIES, RETRY_DELAY_MS, "Location query");
    }

    private static String addToCart(String id, String data) {
        return RetryUtil.withRetry(() -> {
            StringBuilder buffer = new StringBuilder();
            
            try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(5000)
                        .build())
                    .build()) {
                
                HttpPost postRequest = new HttpPost(CART_URL + id);
                StringEntity payload = new StringEntity(data);
                payload.setContentType("application/json");
                postRequest.setEntity(payload);
                HttpResponse res = httpClient.execute(postRequest);

                if (res.getStatusLine().getStatusCode() == 200) {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(res.getEntity().getContent()))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            buffer.append(line);
                        }
                    }
                } else {
                    throw new RuntimeException("Failed with code: " + res.getStatusLine().getStatusCode());
                }
            }
            
            return buffer.toString();
        }, MAX_RETRIES, RETRY_DELAY_MS, "Cart service call");
    }
}
