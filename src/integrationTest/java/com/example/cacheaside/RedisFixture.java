package com.example.cacheaside;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class RedisFixture implements AutoCloseable {
    static final String IMAGE = "redis@sha256:c6eabf748fc7a61dbb5a705c78bcf3d6377b1127a97d0ce965c11c44ba46896f";
    final String host;
    final int port;
    private final GenericContainer<?> container;
    private final String distro;
    private final String binary;
    private final Path log;
    private Process process;

    RedisFixture() throws Exception {
        distro = System.getenv("FLASHSALE_TEST_REDIS_WSL");
        binary = System.getenv("FLASHSALE_TEST_REDIS_BINARY");
        if (distro == null && binary == null) {
            log = null;
            // Preserve Docker's mapped endpoint while restarting the actual Redis process.
            container = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379)
                    .withCommand("sleep", "infinity").waitingFor(Wait.forSuccessfulCommand("true"));
            container.start();
            startContainerServer();
            host = container.getHost();
            port = container.getMappedPort(6379);
        } else {
            if (distro == null || binary == null || !System.getProperty("os.name").startsWith("Windows")
                    || !distro.matches("[A-Za-z0-9._-]{1,64}")
                    || !binary.matches("/[A-Za-z0-9._/-]+/redis-server")) {
                throw new IllegalArgumentException("Explicit native Redis tests require Windows, a WSL distro and "
                        + "an absolute Linux redis-server path. Otherwise use default Testcontainers.");
            }
            container = null;
            log = Files.createTempFile("flashsale-owned-redis-", ".log");
            host = "127.0.0.1";
            try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            System.out.println("ACCEPTANCE BACKEND: explicit real Redis in WSL, NOT Testcontainers.");
            startNative();
        }
        awaitReady();
    }

    String[] arguments() {
        return new String[]{"--spring.data.redis.host=" + host, "--spring.data.redis.port=" + port};
    }

    void stopServer() throws Exception {
        if (container != null) {
            var result = container.execInContainer("redis-cli", "shutdown", "nosave");
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        } else if (process != null && process.isAlive()) {
            var command = nativeCommand(binary.replace("redis-server", "redis-cli"));
            command.addAll(List.of("-h", "127.0.0.1", "-p", Integer.toString(port), "shutdown", "nosave"));
            var stop = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            assertThat(stop.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stop.exitValue()).as("Owned Redis shutdown; log %s", log).isZero();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    void restartServer() throws Exception {
        if (container != null) {
            startContainerServer();
        } else {
            startNative();
        }
        awaitReady();
    }

    private void startContainerServer() throws IOException, InterruptedException {
        var result = container.execInContainer("redis-server", "--bind", "0.0.0.0",
                "--save", "", "--appendonly", "no", "--daemonize", "yes");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private void startNative() throws IOException {
        var command = nativeCommand(binary);
        command.addAll(List.of("--bind", "127.0.0.1", "--port", Integer.toString(port),
                "--save", "", "--appendonly", "no", "--daemonize", "no"));
        process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }

    private List<String> nativeCommand(String executable) {
        return new ArrayList<>(List.of("wsl.exe", "--distribution", distro, "--exec", executable));
    }

    private void awaitReady() {
        var client = RedisClient.create(RedisURI.Builder.redis(host, port)
                .withTimeout(Duration.ofMillis(500)).build());
        try {
            await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() -> {
                if (process != null && !process.isAlive()) {
                    throw new IllegalStateException("Owned Redis exited; inspect " + log);
                }
                try (var connection = client.connect()) {
                    return "PONG".equals(connection.sync().ping());
                }
            });
        } finally {
            client.shutdown();
        }
    }

    @Override
    public void close() throws Exception {
        if (container != null) {
            container.stop();
        } else {
            stopServer();
            Files.deleteIfExists(log);
        }
    }
}
