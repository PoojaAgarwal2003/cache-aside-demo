package com.example.cacheaside;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

final class PurchaseTestApplication implements AutoCloseable {
    static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final PostgresFixture database;
    final ConfigurableApplicationContext context;
    final JdbcTemplate jdbc;
    private final int port;

    PurchaseTestApplication(Class<?> configuration, String... extraArguments) {
        database = new PostgresFixture();
        var arguments = new ArrayList<>(Arrays.asList(database.arguments()));
        arguments.add("--spring.profiles.active=test");
        arguments.addAll(Arrays.asList(extraArguments));
        context = new SpringApplication(CacheAsideApplication.class, configuration)
                .run(arguments.toArray(String[]::new));
        jdbc = context.getBean(JdbcTemplate.class);
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    HttpResponse<String> purchase(long id, String strategy, String client, String key, int quantity) throws Exception {
        return request("POST", "/products/" + id + "/purchase?strategy=" + strategy,
                "{\"quantity\":" + quantity + "}", client, key);
    }

    HttpResponse<String> request(String method, String path, String body, String client, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Client-Id", client).header("Idempotency-Key", key)
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    long fixture(int stock) {
        return jdbc.queryForObject("INSERT INTO products(name,price,stock) VALUES ('Isolated fixture',1,?) RETURNING id",
                Long.class, stock);
    }

    int stock(long id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id);
    }

    int sold(long id) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM purchase_ledger WHERE product_id=?",
                Integer.class, id);
    }

    static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    static <T> List<T> simultaneous(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        try {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return task.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var responses = new ArrayList<T>();
            for (var future : futures) {
                responses.add(future.get(40, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Override
    public void close() throws Exception {
        context.close();
        database.close();
    }
}
