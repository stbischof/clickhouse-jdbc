package com.clickhouse.client.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.clickhouse.client.ClickHouseChecker;
import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseConfig;
import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseFile;
import com.clickhouse.client.ClickHouseInputStream;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseOutputStream;
import com.clickhouse.client.ClickHouseRequest;
import com.clickhouse.client.ClickHouseUtils;
import com.clickhouse.client.cli.config.ClickHouseCommandLineOption;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.client.data.ClickHouseExternalTable;
import com.clickhouse.client.logging.Logger;
import com.clickhouse.client.logging.LoggerFactory;

public class ClickHouseCommandLine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseCommandLine.class);

    public static final String DEFAULT_CLI_ARG_VERSION = "--version";
    public static final String DEFAULT_CLICKHOUSE_CLI_PATH = "clickhouse";
    public static final String DEFAULT_CLIENT_OPTION = "client";
    public static final String DEFAULT_DOCKER_CLI_PATH = "docker";
    public static final String DEFAULT_DOCKER_IMAGE = "clickhouse/clickhouse-server";

    static boolean check(int timeout, String command, String... args) {
        if (ClickHouseChecker.isNullOrBlank(command) || args == null) {
            throw new IllegalArgumentException("Non-blank command and non-null arguments are required");
        }

        List<String> list = new ArrayList<>(args.length + 1);
        list.add(command);
        Collections.addAll(list, args);
        Process process = null;
        try {
            process = new ProcessBuilder(list).start();
            process.getOutputStream().close();
            if (process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                int exitValue = process.exitValue();
                if (exitValue != 0) {
                    log.trace("Command %s exited with value %d", list, exitValue);
                }
                return exitValue == 0;
            } else {
                log.trace("Timed out after waiting %d ms for command %s to complete", timeout, list);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.trace("Failed to check command %s due to: %s", list, e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }

        return false;
    }

    static void dockerCommand(ClickHouseConfig config, String hostDir, String containerDir, int timeout,
            List<String> commands) {
        String cli = (String) config.getOption(ClickHouseCommandLineOption.DOCKER_CLI_PATH);
        if (ClickHouseChecker.isNullOrBlank(cli)) {
            cli = DEFAULT_DOCKER_CLI_PATH;
        }
        if (!check(timeout, cli, DEFAULT_CLI_ARG_VERSION)) {
            throw new IllegalStateException("Docker command-line is not available: " + cli);
        } else {
            commands.add(cli);
        }

        String img = (String) config.getOption(ClickHouseCommandLineOption.CLICKHOUSE_DOCKER_IMAGE);
        if (ClickHouseChecker.isNullOrBlank(img)) {
            img = DEFAULT_DOCKER_IMAGE;
        }
        String str = (String) config.getOption(ClickHouseCommandLineOption.CLI_CONTAINER_ID);
        if (!ClickHouseChecker.isNullOrBlank(str)) {
            if (!check(timeout, cli, "exec", str, DEFAULT_CLICKHOUSE_CLI_PATH, DEFAULT_CLIENT_OPTION,
                    DEFAULT_CLI_ARG_VERSION)) {
                synchronized (ClickHouseCommandLine.class) {
                    if (!check(timeout, cli, "exec", str, DEFAULT_CLICKHOUSE_CLI_PATH, DEFAULT_CLIENT_OPTION,
                            DEFAULT_CLI_ARG_VERSION)
                            && !check(timeout, cli, "run", "--rm", "--name", str, "-v", hostDir + ':' + containerDir,
                                    "-d", img, "tail", "-f", "/dev/null")) {
                        throw new IllegalStateException("Failed to start new container: " + str);
                    }
                }
            }
            // reuse the existing container
            commands.add("exec");
            commands.add("-i");
            commands.add(str);
        } else { // create new container for each query
            if (!check(timeout, cli, "run", "--rm", img, DEFAULT_CLICKHOUSE_CLI_PATH, DEFAULT_CLIENT_OPTION,
                    DEFAULT_CLI_ARG_VERSION)) {
                throw new IllegalStateException("Invalid ClickHouse docker image: " + img);
            }
            commands.add("run");
            commands.add("--rm");
            commands.add("-i");
            commands.add("-v");
            commands.add(hostDir + ':' + containerDir);
            commands.add(img);
        }

        commands.add(DEFAULT_CLICKHOUSE_CLI_PATH);
    }

    static Process startProcess(ClickHouseNode server, ClickHouseRequest<?> request) {
        final ClickHouseConfig config = request.getConfig();
        final int timeout = config.getSocketTimeout();

        String hostDir = (String) config.getOption(ClickHouseCommandLineOption.CLI_WORK_DIRECTORY);
        hostDir = ClickHouseUtils.normalizeDirectory(
                ClickHouseChecker.isNullOrBlank(hostDir) ? System.getProperty("java.io.tmpdir") : hostDir);
        String containerDir = (String) config.getOption(ClickHouseCommandLineOption.CLI_CONTAINER_DIRECTORY);
        if (ClickHouseChecker.isNullOrBlank(containerDir)) {
            containerDir = "/tmp/";
        } else {
            containerDir = ClickHouseUtils.normalizeDirectory(containerDir);
        }

        List<String> commands = new LinkedList<>();
        String cli = (String) config.getOption(ClickHouseCommandLineOption.CLICKHOUSE_CLI_PATH);
        if (ClickHouseChecker.isNullOrBlank(cli)) {
            cli = DEFAULT_CLICKHOUSE_CLI_PATH;
        }
        if (!check(timeout, cli, DEFAULT_CLIENT_OPTION, DEFAULT_CLI_ARG_VERSION)) {
            // fallback to docker
            dockerCommand(config, hostDir, containerDir, timeout, commands);
        } else {
            commands.add(cli);
            containerDir = hostDir;
        }
        commands.add(DEFAULT_CLIENT_OPTION);

        if (config.isSsl()) {
            commands.add("--secure");
        }
        commands.add("--compression=".concat(config.isResponseCompressed() ? "1" : "0"));
        commands.add("--host=".concat(server.getHost()));
        commands.add("--port=".concat(Integer.toString(server.getPort())));

        String str = server.getDatabase(config);
        if (!ClickHouseChecker.isNullOrBlank(str)) {
            commands.add("--database=".concat(str));
        }
        str = (String) config.getOption(ClickHouseCommandLineOption.CLI_CONFIG_FILE);
        if ((boolean) config.getOption(ClickHouseCommandLineOption.USE_CLI_CONFIG)
                && !ClickHouseChecker.isNullOrBlank(str) && Files.exists(Paths.get(str))) {
            commands.add("--config-file=".concat(str));
        } else {
            ClickHouseCredentials credentials = server.getCredentials(config);
            str = credentials.getUserName();
            if (!ClickHouseChecker.isNullOrBlank(str)) {
                commands.add("--user=".concat(str));
            }
            str = credentials.getPassword();
            if (!ClickHouseChecker.isNullOrBlank(str)) {
                commands.add("--password=".concat(str));
            }
        }
        commands.add("--format=".concat(config.getFormat().name()));

        str = request.getQueryId().orElse("");
        if (!ClickHouseChecker.isNullOrBlank(str)) {
            commands.add("--query_id=".concat(str));
        }
        commands.add("--query=".concat(request.getStatements(false).get(0)));

        for (ClickHouseExternalTable table : request.getExternalTables()) {
            ClickHouseFile tableFile = table.getFile();
            commands.add("--external");
            String filePath;
            if (!tableFile.isAvailable() || !tableFile.getFile().getAbsolutePath().startsWith(hostDir)) {
                // creating a hard link is faster but it's not platform-independent
                File f = ClickHouseInputStream.save(
                        Paths.get(hostDir, "chc_".concat(UUID.randomUUID().toString())).toFile(),
                        table.getContent(), config.getWriteBufferSize(), config.getSocketTimeout(), true);
                filePath = containerDir.concat(f.getName());
            } else {
                filePath = tableFile.getFile().getAbsolutePath();
                if (!hostDir.equals(containerDir)) {
                    filePath = Paths.get(containerDir, filePath.substring(hostDir.length())).toFile().getAbsolutePath();
                }
            }
            commands.add("--file=" + filePath);
            if (!ClickHouseChecker.isNullOrEmpty(table.getName())) {
                commands.add("--name=".concat(table.getName()));
            }
            if (table.getFormat() != null) {
                commands.add("--format=".concat(table.getFormat().name()));
            }
            commands.add("--structure=".concat(table.getStructure()));
        }

        Map<String, Object> settings = request.getSettings();
        Object value = settings.get("max_result_rows");
        if (value instanceof Number) {
            long maxRows = ((Number) value).longValue();
            if (maxRows > 0L) {
                commands.add("--limit=".concat(Long.toString(maxRows)));
            }
        }
        value = settings.get("result_overflow_mode");
        if (value != null) {
            commands.add("--result_overflow_mode=".concat(value.toString()));
        }
        if ((boolean) config.getOption(ClickHouseCommandLineOption.USE_PROFILE_EVENTS)) {
            commands.add("--print-profile-events");
            commands.add("--profile-events-delay-ms=-1");
        }

        log.debug("Query: %s", str);
        ProcessBuilder builder = new ProcessBuilder(commands);
        String workDirectory = (String) config.getOption(
                ClickHouseCommandLineOption.CLI_WORK_DIRECTORY);
        if (!ClickHouseChecker.isNullOrBlank(workDirectory)) {
            Path p = Paths.get(workDirectory);
            if (Files.isDirectory(p)) {
                builder.directory(p.toFile());
            }
        }

        if (request.hasOutputStream()) {
            final ClickHouseOutputStream chOutput = request.getOutputStream().get();
            final ClickHouseFile outputFile = chOutput.getUnderlyingFile();

            if (outputFile.isAvailable()) {
                File f = outputFile.getFile();
                if (hostDir.equals(containerDir)) {
                    builder.redirectOutput(f);
                } else if (f.getAbsolutePath().startsWith(hostDir)) {
                    String relativePath = f.getAbsolutePath().substring(hostDir.length());
                    builder.redirectOutput(new File(containerDir.concat(relativePath)));
                } else {
                    String fileName = f.getName();
                    int len = fileName.length();
                    int index = fileName.indexOf('.', 1);
                    String uuid = UUID.randomUUID().toString();
                    if (index > 0 && index + 1 < len) {
                        fileName = new StringBuilder(len + uuid.length() + 1).append(fileName.substring(0, index))
                                .append('_').append(uuid).append(fileName.substring(index)).toString();
                    } else {
                        fileName = new StringBuilder(len + uuid.length() + 1).append(fileName).append('_')
                                .append(UUID.randomUUID().toString()).toString();
                    }
                    Path newPath = Paths.get(hostDir, fileName);
                    try {
                        f = Files.createLink(newPath, f.toPath()).toFile();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } catch (UnsupportedOperationException e) {
                        try {
                            f = ClickHouseInputStream.save(newPath.toFile(), new FileInputStream(f),
                                    config.getWriteBufferSize(), timeout, true);
                        } catch (FileNotFoundException exp) {
                            throw new UncheckedIOException(exp);
                        }
                    }
                }
                builder.redirectOutput(f);
            }
        }
        final Optional<ClickHouseInputStream> in = request.getInputStream();
        try {
            final Process process;
            if (in.isPresent()) {
                final ClickHouseInputStream chInput = in.get();
                final File inputFile;
                if (chInput.getUnderlyingFile().isAvailable()) {
                    inputFile = chInput.getUnderlyingFile().getFile();
                } else {
                    CompletableFuture<File> data = ClickHouseClient.submit(() -> {
                        File tmp = File.createTempFile("tmp", "data");
                        tmp.deleteOnExit();
                        try (ClickHouseOutputStream out = ClickHouseOutputStream.of(new FileOutputStream(tmp))) {
                            request.getInputStream().get().pipe(out);
                        }
                        return tmp;
                    });
                    inputFile = data.get(timeout, TimeUnit.MILLISECONDS);
                }
                process = builder.redirectInput(inputFile).start();
            } else {
                process = builder.start();
                process.getOutputStream().close();
            }
            return process;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (CancellationException | ExecutionException | TimeoutException e) {
            throw new CompletionException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final ClickHouseNode server;
    private final ClickHouseRequest<?> request;

    private final Process process;

    private String error;

    public ClickHouseCommandLine(ClickHouseNode server, ClickHouseRequest<?> request) {
        this.server = server;
        this.request = request;

        this.process = startProcess(server, request);
        this.error = null;
    }

    public ClickHouseInputStream getInputStream() throws IOException {
        ClickHouseOutputStream out = request.getOutputStream().orElse(null);
        if (out != null && !out.getUnderlyingFile().isAvailable()) {
            try (OutputStream o = out) {
                ClickHouseInputStream.pipe(process.getInputStream(), o, request.getConfig().getWriteBufferSize());
            }
            return ClickHouseInputStream.empty();
        } else {
            return ClickHouseInputStream.of(process.getInputStream(), request.getConfig().getReadBufferSize());
        }
    }

    IOException getError() {
        if (error == null) {
            int bufferSize = (int) ClickHouseClientOption.BUFFER_SIZE.getDefaultValue();
            try (ByteArrayOutputStream output = new ByteArrayOutputStream(bufferSize)) {
                ClickHouseInputStream.pipe(process.getErrorStream(), output, bufferSize);
                error = new String(output.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                error = "";
            }
            try {
                int exitValue = process.waitFor();
                if (exitValue != 0) {
                    if (error.isEmpty()) {
                        error = ClickHouseUtils.format("Command exited with value %d", exitValue);
                    } else {
                        int index = error.trim().indexOf('\n');
                        error = index > 0 ? error.substring(index + 1) : error;
                    }
                } else {
                    if (!error.isEmpty()) {
                        // TODO update response summary
                        log.trace(() -> {
                            for (String line : error.split("\n")) {
                                log.trace(line);
                            }
                            return "";
                        });
                    }
                    error = "";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new CompletionException(e);
            }
        }
        return !ClickHouseChecker.isNullOrBlank(error) ? new IOException(error) : null;
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
