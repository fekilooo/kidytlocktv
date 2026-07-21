package free.rm.skytube.businessobjects;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import free.rm.skytube.app.SkyTubeApp;

public final class DiagnosticFileLogger {
    public static final String HTTP_LOG_FILE_NAME = "skytube_http_log.txt";
    public static final String CRASH_LOG_FILE_NAME = "skytube_crash_log.txt";
    public static final String DEBUG_LOG_FILE_NAME = "skytube_debug_log.txt";
    public static final String TRENDING_LOG_FILE_NAME = "skytube_trending_log.txt";
    private static final String EXPORT_DIR_NAME = "SkyTubeLogs";

    private DiagnosticFileLogger() {
    }

    public static void appendHttp(@NonNull String content) {
        append(HTTP_LOG_FILE_NAME, content);
    }

    public static void appendCrash(@NonNull String content) {
        append(CRASH_LOG_FILE_NAME, content);
    }

    public static void append(@NonNull String fileName, @NonNull String content) {
        final Context context = SkyTubeApp.getContext();
        if (context == null) {
            return;
        }

        try {
            File logFile = getLogFile(context, fileName);
            if (logFile == null) {
                Logger.e(DiagnosticFileLogger.class, "Unable to access output file for %s", fileName);
                return;
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                writer.write(content);
                if (!content.endsWith("\n")) {
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            Logger.e(DiagnosticFileLogger.class, e, "Unable to write diagnostic file %s", fileName);
        }
    }

    @NonNull
    public static String buildCrashEntry(@NonNull Thread thread, @NonNull Throwable throwable) {
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.flush();

        return new StringBuilder()
                .append(new Date()).append('\n')
                .append("thread=").append(thread.getName()).append('\n')
                .append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n')
                .append(stringWriter)
                .append("---\n")
                .toString();
    }

    @NonNull
    public static String getAbsoluteLogPath(@NonNull String fileName) {
        final Context context = SkyTubeApp.getContext();
        if (context == null) {
            return fileName;
        }
        final File logFile = getLogFile(context, fileName);
        return logFile != null ? logFile.getAbsolutePath() : fileName;
    }

    @NonNull
    public static ExportResult exportAllLogs() {
        final Context context = SkyTubeApp.getContext();
        if (context == null) {
            return new ExportResult(null, null, Arrays.asList("App context unavailable"));
        }

        final File sourceDir = getLogDirectory(context);
        final File exportDir = getVisibleExportDirectory(context);
        final List<String> exportedFiles = new ArrayList<>();

        if (sourceDir == null) {
            return new ExportResult(null, exportDir, Arrays.asList("Log source directory unavailable"));
        }
        if (exportDir == null) {
            return new ExportResult(sourceDir, null, Arrays.asList("Visible export directory unavailable"));
        }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            return new ExportResult(sourceDir, exportDir, Arrays.asList("Unable to create export directory"));
        }

        for (String fileName : getKnownLogFileNames()) {
            final File sourceFile = new File(sourceDir, fileName);
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                continue;
            }
            final File exportedFile = new File(exportDir, fileName);
            try {
                copyFile(sourceFile, exportedFile);
                exportedFiles.add(exportedFile.getAbsolutePath());
            } catch (IOException e) {
                exportedFiles.add("FAILED: " + sourceFile.getAbsolutePath() + " -> " + e.getMessage());
            }
        }

        if (exportedFiles.isEmpty()) {
            exportedFiles.add("No log files were found to export.");
        }

        return new ExportResult(sourceDir, exportDir, exportedFiles);
    }

    @NonNull
    public static ExportResult exportAllLogsToTreeUri(@NonNull Context context, @NonNull Uri treeUri) {
        final File sourceDir = getLogDirectory(context);
        final List<String> exportedFiles = new ArrayList<>();

        if (sourceDir == null) {
            return new ExportResult(null, null, Arrays.asList("Log source directory unavailable"));
        }

        final DocumentFile pickedDir = DocumentFile.fromTreeUri(context, treeUri);
        if (pickedDir == null || !pickedDir.canWrite()) {
            return new ExportResult(sourceDir, null, Arrays.asList("Selected folder is not writable"));
        }

        DocumentFile exportDir = pickedDir.findFile(EXPORT_DIR_NAME);
        if (exportDir == null) {
            exportDir = pickedDir.createDirectory(EXPORT_DIR_NAME);
        }
        if (exportDir == null || !exportDir.canWrite()) {
            return new ExportResult(sourceDir, null, Arrays.asList("Unable to create SkyTubeLogs directory"));
        }

        for (String fileName : getKnownLogFileNames()) {
            final File sourceFile = new File(sourceDir, fileName);
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                continue;
            }

            try {
                DocumentFile targetFile = exportDir.findFile(fileName);
                if (targetFile != null) {
                    targetFile.delete();
                }
                targetFile = exportDir.createFile("text/plain", fileName);
                if (targetFile == null) {
                    exportedFiles.add("FAILED: " + fileName + " -> unable to create target");
                    continue;
                }
                copyFileToUri(context, sourceFile, targetFile.getUri());
                exportedFiles.add(fileName + " -> " + targetFile.getUri());
            } catch (IOException e) {
                exportedFiles.add("FAILED: " + sourceFile.getAbsolutePath() + " -> " + e.getMessage());
            }
        }

        if (exportedFiles.isEmpty()) {
            exportedFiles.add("No log files were found to export.");
        }

        return new ExportResult(sourceDir, null, exportedFiles);
    }

    @NonNull
    public static ExportResult exportCombinedLogsToUri(@NonNull Context context, @NonNull Uri destinationUri) {
        final File sourceDir = getLogDirectory(context);
        if (sourceDir == null) {
            return new ExportResult(null, null, Arrays.asList("Log source directory unavailable"));
        }

        final String combinedText = buildCombinedLogsText(sourceDir);
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(destinationUri, "w")) {
            if (outputStream == null) {
                return new ExportResult(sourceDir, null, Arrays.asList("Unable to open selected output file"));
            }
            outputStream.write(combinedText.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return new ExportResult(sourceDir, null, Arrays.asList("Combined log exported to " + destinationUri));
        } catch (IOException e) {
            return new ExportResult(sourceDir, null, Arrays.asList("Failed to export combined log: " + e.getMessage()));
        }
    }

    @Nullable
    private static File getLogFile(@NonNull Context context, @NonNull String fileName) {
        final File logDirectory = getLogDirectory(context);
        if (logDirectory == null) {
            return null;
        }
        return new File(logDirectory, fileName);
    }

    @Nullable
    private static File getLogDirectory(@NonNull Context context) {
        File outputDir = context.getExternalFilesDir(null);
        if (outputDir != null && (outputDir.exists() || outputDir.mkdirs())) {
            return outputDir;
        }

        outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (outputDir != null && (outputDir.exists() || outputDir.mkdirs())) {
            return outputDir;
        }
        return null;
    }

    @Nullable
    private static File getVisibleExportDirectory(@NonNull Context context) {
        File outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (outputDir != null && (outputDir.exists() || outputDir.mkdirs())) {
            return new File(outputDir, EXPORT_DIR_NAME);
        }

        outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (outputDir != null && (outputDir.exists() || outputDir.mkdirs())) {
            return new File(outputDir, EXPORT_DIR_NAME);
        }
        return null;
    }

    @NonNull
    private static List<String> getKnownLogFileNames() {
        return Arrays.asList(DEBUG_LOG_FILE_NAME, HTTP_LOG_FILE_NAME, CRASH_LOG_FILE_NAME, TRENDING_LOG_FILE_NAME);
    }

    private static void copyFile(@NonNull File sourceFile, @NonNull File destinationFile) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(sourceFile);
             FileOutputStream outputStream = new FileOutputStream(destinationFile, false)) {
            copyStreams(inputStream, outputStream);
            outputStream.getFD().sync();
        }
    }

    private static void copyFileToUri(@NonNull Context context,
                                      @NonNull File sourceFile,
                                      @NonNull Uri destinationUri) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(sourceFile);
             OutputStream outputStream = context.getContentResolver().openOutputStream(destinationUri, "w")) {
            if (outputStream == null) {
                throw new IOException("Unable to open target output stream");
            }
            copyStreams(inputStream, outputStream);
        }
    }

    private static void copyStreams(@NonNull InputStream inputStream,
                                    @NonNull OutputStream outputStream) throws IOException {
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        outputStream.flush();
    }

    @NonNull
    private static String buildCombinedLogsText(@NonNull File sourceDir) {
        final StringBuilder builder = new StringBuilder();
        builder.append("SkyTube diagnostic logs").append('\n')
                .append("generatedAt=").append(new Date()).append('\n')
                .append("sourceDir=").append(sourceDir.getAbsolutePath()).append('\n')
                .append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n')
                .append("====").append('\n');

        for (String fileName : getKnownLogFileNames()) {
            builder.append('\n')
                    .append("===== ").append(fileName).append(" =====").append('\n');
            final File sourceFile = new File(sourceDir, fileName);
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                builder.append("MISSING").append('\n');
                continue;
            }
            try (InputStream inputStream = new FileInputStream(sourceFile)) {
                final byte[] bytes = new byte[(int) sourceFile.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = inputStream.read(bytes, offset, bytes.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                builder.append(new String(bytes, 0, offset, StandardCharsets.UTF_8));
                if (offset == 0 || builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
            } catch (IOException e) {
                builder.append("FAILED TO READ: ").append(e.getMessage()).append('\n');
            }
        }
        return builder.toString();
    }

    public static final class ExportResult {
        @Nullable public final File sourceDir;
        @Nullable public final File exportDir;
        @NonNull public final List<String> exportedFiles;

        ExportResult(@Nullable File sourceDir, @Nullable File exportDir, @NonNull List<String> exportedFiles) {
            this.sourceDir = sourceDir;
            this.exportDir = exportDir;
            this.exportedFiles = exportedFiles;
        }
    }
}
