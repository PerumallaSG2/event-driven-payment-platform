package com.gopi.payments.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One service as its own {@code java} process: its {@code target/classes} plus the runtime classpath its build wrote
 * ({@code maven-dependency-plugin:build-classpath}). Same port on every restart, so the other services and the
 * ledger's reconciliation keep finding it. {@link #kill()} is SIGKILL; an armed launch adds a crash interceptor
 * (see {@code com.gopi.payments.e2e.crash}) that halts the process at a chosen event.
 */
final class ServiceProcess {

    /** Where an armed launch halts: before sending an event type, or on receiving one. */
    record CrashPoint(String kind, String eventType) {

        static CrashPoint beforeSend(String eventType) {
            return new CrashPoint("before-send", eventType);
        }

        static CrashPoint onReceive(String eventType) {
            return new CrashPoint("on-receive", eventType);
        }
    }

    private static final Path LOGS = Path.of("target", "e2e-logs");
    private static final Path CRASH_CLASSES = Path.of("target", "classes");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    final String name;
    final int port;
    private final Path moduleDir;
    private final String mainClass;
    private final List<String> args;
    private Process process;
    private int launches;

    ServiceProcess(String name, int port, String mainClass, List<String> args) {
        this.name = name;
        this.port = port;
        this.moduleDir = Path.of("..", name);
        this.mainClass = mainClass;
        this.args = List.copyOf(args);
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port);
    }

    void start() {
        start(null);
    }

    /** Starts a new process (the previous one must be gone); does not wait for it to be ready. */
    void start(CrashPoint crash) {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException(name + " is still running");
        }
        try {
            String classpath = moduleDir.resolve("target/classes").toAbsolutePath() + java.io.File.pathSeparator
                    + Files.readString(moduleDir.resolve("target/runtime-classpath.txt")).trim();
            List<String> command = new ArrayList<>(List.of(javaBinary(),
                    "-Xmx256m", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                    "-XX:-UsePerfData", "-Dspring.output.ansi.enabled=never"));
            List<String> appArgs = new ArrayList<>(args);
            if (crash != null) {
                classpath += java.io.File.pathSeparator + CRASH_CLASSES.toAbsolutePath();
                command.add("-De2e.crash." + crash.kind() + "=" + crash.eventType());
                appArgs.add(crash.kind().equals("before-send")
                        ? "--spring.kafka.producer.properties.interceptor.classes=com.gopi.payments.e2e.crash.HaltBeforeSend"
                        : "--spring.kafka.consumer.properties.interceptor.classes=com.gopi.payments.e2e.crash.HaltOnReceive");
            }
            command.addAll(List.of("-cp", classpath, mainClass));
            command.add("--server.port=" + port);
            command.addAll(appArgs);
            Files.createDirectories(LOGS);
            launches++;
            Path log = LOGS.resolve(name + "-" + launches + ".log");
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start " + name, e);
        }
    }

    /** Waits until the HTTP port answers (any status). */
    void awaitReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpRequest probe = HttpRequest.newBuilder(baseUrl().resolve("/")).timeout(Duration.ofSeconds(1)).build();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with " + process.exitValue() + " while starting; see "
                        + currentLog());
            }
            try {
                HTTP.send(probe, HttpResponse.BodyHandlers.discarding());
                return;
            } catch (IOException e) {
                sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(name + " not ready within " + timeout + "; see " + currentLog());
    }

    void startAndAwait(CrashPoint crash) {
        start(crash);
        awaitReady(Duration.ofSeconds(120));
    }

    /** SIGKILL: the process gets no chance to finish anything. */
    void kill() {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for an armed process to halt itself; returns its exit code. */
    int awaitCrash(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(name + " did not crash within " + timeout + "; see " + currentLog());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return process.exitValue();
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    String currentLog() {
        return LOGS.resolve(name + "-" + launches + ".log").toAbsolutePath().toString();
    }

    String logText() {
        try {
            return Files.readString(Path.of(currentLog()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
