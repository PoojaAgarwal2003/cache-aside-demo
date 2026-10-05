package com.example.cacheaside;

import com.example.cacheaside.purchase.PurchaseProbe;
import com.example.cacheaside.purchase.PurchaseRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Runs only on the integration-test classpath, never in the executable app JAR. */
public final class PurchaseCrashChild {
    public static void main(String[] arguments) throws IOException {
        var app = new SpringApplication(CacheAsideApplication.class, CrashConfiguration.class).run(arguments);
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        Files.writeString(Path.of(app.getEnvironment().getRequiredProperty("crash.ready-file")), Integer.toString(port));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CrashConfiguration {
        @Bean
        PurchaseProbe crashProbe(Environment environment) {
            String phase = environment.getRequiredProperty("crash.phase");
            Path marker = Path.of(environment.getRequiredProperty("crash.boundary-file"));
            return new PurchaseProbe() {
                @Override
                public void beforeCommit(PurchaseRequest request, UUID purchaseId) {
                    if ("BEFORE_COMMIT".equals(phase)) {
                        pause();
                    }
                }

                @Override
                public void afterCommit(PurchaseRequest request, UUID purchaseId) {
                    if ("AFTER_COMMIT".equals(phase)) {
                        pause();
                    }
                }

                private void pause() {
                    try {
                        Files.writeString(marker, phase);
                        new CountDownLatch(1).await(); // Parent forcibly kills this exact owned Java process.
                    } catch (IOException failure) {
                        throw new IllegalStateException("Cannot signal crash boundary", failure);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Crash boundary interrupted", interrupted);
                    }
                }
            };
        }
    }
}
