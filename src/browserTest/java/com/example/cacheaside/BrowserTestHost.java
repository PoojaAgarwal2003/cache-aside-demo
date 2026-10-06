package com.example.cacheaside;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Test-only stdin control channel; no fault-control endpoint enters the shipped JAR. */
public final class BrowserTestHost implements AutoCloseable {
    private final PostgresFixture database;
    private final RedisFixture redis;
    private final Path jar;
    private final Path directory;
    private final int port;
    private Process app;
    private final JsonMapper json = new JsonMapper();

    private BrowserTestHost(PostgresFixture database, RedisFixture redis, Path jar, Path directory) throws Exception {
        this.database = database; this.redis = redis; this.jar = jar; this.directory = directory;
        Files.createDirectories(directory);
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
    }

    private void start(String profile) throws Exception {
        if (app != null && app.isAlive()) { throw new IllegalStateException("Owned app already running."); }
        var arguments = new ArrayList<>(List.of("-jar", jar.toAbsolutePath().toString()));
        arguments.addAll(List.of(database.arguments()));
        arguments.addAll(List.of(redis.arguments()));
        arguments.removeIf(argument -> argument.startsWith("--server.port=") || argument.startsWith("--lab.demo-enabled=")
                || argument.startsWith("--lab.rate-limit.enabled=") || argument.startsWith("--lab.read-delay-ms=")
                || argument.startsWith("--lab.purchase-delay-ms="));
        arguments.addAll(List.of("--server.port=" + port, "--spring.profiles.active=" + profile,
                "--lab.read-delay-ms=200", "--lab.purchase-delay-ms=25", "--lab.rate-limit.enabled=true"));
        var argumentFile = directory.resolve("app.args");
        Files.writeString(argumentFile, arguments.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining("\n")));
        app = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argumentFile.toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("app.log").toFile())).start();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) { throw new IllegalStateException("Packaged browser app exited; inspect browser/app.log."); }
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(url() + "/status"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && "READY".equals(json.readTree(response.body()).path("cache").asString())) { return; }
            } catch (java.io.IOException unavailable) {
                // Bounded startup wait; failure below retains the packaged log.
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Packaged browser app did not become ready within 60 seconds.");
    }

    private String url() { return "http://127.0.0.1:" + port; }
    private void stopApp() throws Exception {
        if (app != null && app.isAlive()) {
            app.destroyForcibly();
            if (!app.waitFor(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Owned app did not exit."); }
        }
    }
    private void finalizationFault(boolean enabled) throws Exception {
        try (var connection = DriverManager.getConnection(database.scopedUrl(), database.username, database.password);
             var statement = connection.createStatement()) {
            if (enabled) {
                statement.execute("""
                        CREATE FUNCTION browser_reject_finish() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'browser-test finalization failure'; END $$
                        """);
                statement.execute("""
                        CREATE TRIGGER browser_reject_finish BEFORE UPDATE OF active ON demo_runs
                        FOR EACH ROW WHEN (NEW.active=false) EXECUTE FUNCTION browser_reject_finish()
                        """);
            } else {
                statement.execute("DROP TRIGGER browser_reject_finish ON demo_runs");
                statement.execute("DROP FUNCTION browser_reject_finish()");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (var database = new PostgresFixture();
             var redis = new RedisFixture();
             var host = new BrowserTestHost(database, redis, Path.of(args[0]), Path.of(args[1]));
             var input = new BufferedReader(new InputStreamReader(System.in))) {
            host.start("demo");
            host.reply(Map.of("id", 0, "url", host.url()));
            String line;
            while ((line = input.readLine()) != null) {
                var request = host.json.readTree(line);
                String command = request.path("command").asString();
                try {
                    switch (command) {
                        case "STOP_REDIS" -> host.redis.stopServer(true);
                        case "START_REDIS" -> host.redis.restartServer();
                        case "STOP_APP" -> host.stopApp();
                        case "START_APP" -> host.start("demo");
                        case "RESTART_APP" -> { host.stopApp(); host.start("demo"); }
                        case "DEFAULT_PROFILE" -> { host.stopApp(); host.start("default"); }
                        case "BLOCK_FINALIZATION" -> host.finalizationFault(true);
                        case "UNBLOCK_FINALIZATION" -> host.finalizationFault(false);
                        case "CLOSE" -> { host.reply(Map.of("id", request.path("id").asInt(), "ok", true)); return; }
                        default -> throw new IllegalArgumentException("Unknown fixed browser-host command.");
                    }
                    host.reply(Map.of("id", request.path("id").asInt(), "ok", true));
                } catch (Exception failure) {
                    failure.printStackTrace(System.err);
                    host.reply(Map.of("id", request.path("id").asInt(), "error", failure.toString()));
                }
            }
        }
    }
    private void reply(Object value) { System.out.println("LAB_BROWSER " + json.writeValueAsString(value)); System.out.flush(); }
    @Override public void close() throws Exception {
        stopApp();
    }
}
