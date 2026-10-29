package me.ghosthacks96.POS_System.utilities;

import me.ghosthacks96.POS_System.POS_System;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class Logger implements AutoCloseable {
    private static final int MAX_RECENT_ENTRIES = 1_000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String RESET = "\u001B[0m";

    private final BufferedWriter fileWriter;
    private final boolean useColors;
    private final Deque<LogEntry> recentEntries = new ArrayDeque<>();
    private long nextSequence = 1;
    private boolean closed;

    public Logger() {
        this(Path.of("logs", "pos-system.log"));
    }

    public Logger(Path logFile) {
        Objects.requireNonNull(logFile, "logFile");
        try {
            Path absoluteLogFile = logFile.toAbsolutePath();
            Path parent = absoluteLogFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            fileWriter = Files.newBufferedWriter(
                    absoluteLogFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to open log file: " + logFile, exception);
        }
        useColors = supportsAnsiColors();
    }

    public synchronized void debug(String message) {
       if(POS_System.DEBUG) log("DEBUG", message, "\u001B[36m", System.out);
    }

    public synchronized void info(String message) {
        log("INFO", message, null, System.out);
    }

    public synchronized void warning(String message) {
        log("WARNING", message, "\u001B[33m", System.err);
    }

    public synchronized void severe(String message) {
        log("SEVERE", message, "\u001B[31m", System.err);
    }

    private void log(String level, String message, String color, java.io.PrintStream output) {
        if (closed) {
            throw new IllegalStateException("Logger is closed");
        }

        String line = LocalDateTime.now().format(TIMESTAMP_FORMAT)
                + " [" + level + "] " + Objects.requireNonNull(message, "message");
        recentEntries.addLast(new LogEntry(nextSequence++, line));
        if (recentEntries.size() > MAX_RECENT_ENTRIES) {
            recentEntries.removeFirst();
        }
        try {
            fileWriter.write(line);
            fileWriter.newLine();
            fileWriter.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write to the log file", exception);
        }

        if (useColors && color != null) {
            output.println(color + line + RESET);
        } else {
            output.println(line);
        }
    }

    public synchronized List<LogEntry> entriesAfter(long sequence) {
        return recentEntries.stream()
                .filter(entry -> entry.sequence() > sequence)
                .toList();
    }

    public record LogEntry(long sequence, String line) {
    }

    private static boolean supportsAnsiColors() {
        if (System.getenv("NO_COLOR") != null || "0".equals(System.getenv("CLICOLOR"))) {
            return false;
        }

        String terminal = System.getenv("TERM");
        return (terminal != null && !terminal.isEmpty() && !"dumb".equalsIgnoreCase(terminal))
                || System.getenv("WT_SESSION") != null
                || System.getenv("ANSICON") != null
                || "ON".equalsIgnoreCase(System.getenv("ConEmuANSI"));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            fileWriter.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to close the log file", exception);
        }
    }
}
