package cn.junruo.click;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

final class RawInputClient implements Closeable {
    private static final String DAEMON_CLASS = "cn.junruo.click.RawInputDaemon";

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final RawInputProfile profile;
    private boolean closed;

    private RawInputClient(Process process, BufferedWriter writer, BufferedReader reader,
                           RawInputProfile profile) {
        this.process = process;
        this.writer = writer;
        this.reader = reader;
        this.profile = profile;
    }

    static RawInputClient connect(Context context, RawInputProfile profile) throws IOException {
        String sourceDir = context.getApplicationInfo().sourceDir;
        String command = "CLASSPATH=" + shellQuote(sourceDir)
                + " app_process /system/bin " + DAEMON_CLASS + " " + profile.daemonArguments();
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        RawInputClient client = new RawInputClient(
                process,
                new BufferedWriter(new OutputStreamWriter(process.getOutputStream())),
                new BufferedReader(new InputStreamReader(process.getInputStream())),
                profile);
        try {
            client.awaitPrefix("READY", 5000);
            client.sendAndAwait("SELFTEST", 3000);
            return client;
        } catch (IOException e) {
            client.closeQuietly();
            throw e;
        }
    }

    RawInputProfile getProfile() {
        return profile;
    }

    synchronized void tap(int rawX, int rawY) throws IOException {
        sendAndAwait("TAP " + rawX + " " + rawY, 3000);
    }

    synchronized void forceRelease() throws IOException {
        sendAndAwait("RELEASE", 2000);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        boolean interrupted = Thread.interrupted();
        try {
            try { sendAndAwait("RELEASE", 1000); } catch (Exception ignored) {}
            try {
                writer.write("EXIT");
                writer.newLine();
                writer.flush();
            } catch (Exception ignored) {}
        } finally {
            closed = true;
            try { writer.close(); } catch (Exception ignored) {}
            try { reader.close(); } catch (Exception ignored) {}
            try { process.destroy(); } catch (Exception ignored) {}
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly() {
        try { close(); } catch (Exception ignored) {}
    }

    private void sendAndAwait(String command, long timeoutMs) throws IOException {
        if (closed || !isAlive(process)) throw new IOException("Raw input daemon is not running");
        writer.write(command);
        writer.newLine();
        writer.flush();
        String response = awaitPrefix("OK", timeoutMs);
        if ("OK".equals(response)) return;
        if (response.startsWith("ERR")) {
            throw new IOException(response.length() > 4 ? response.substring(4) : "Raw input command failed");
        }
        throw new IOException("Unexpected raw input response: " + response);
    }

    private String awaitPrefix(String requiredPrefix, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMs);
        String lastLine = null;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Raw input interrupted");
            if (!isAlive(process)) throw new IOException("Raw input daemon exited: " + lastLine);
            try {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) throw new IOException("Raw input output closed");
                    lastLine = line.trim();
                    if (requiredPrefix == null || lastLine.startsWith(requiredPrefix)
                            || lastLine.startsWith("ERR") || lastLine.startsWith("FATAL")) {
                        if (lastLine.startsWith("ERR") || lastLine.startsWith("FATAL")) {
                            throw new IOException(lastLine);
                        }
                        return lastLine;
                    }
                } else {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Raw input interrupted", e);
            }
        }
        throw new IOException("Raw input response timed out: " + lastLine);
    }

    private static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
