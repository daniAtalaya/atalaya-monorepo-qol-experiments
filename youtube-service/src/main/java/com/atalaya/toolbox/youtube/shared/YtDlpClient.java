package com.atalaya.toolbox.youtube.shared;

import com.atalaya.toolbox.youtube.download.provider.YoutubeMediaDownloader;
import com.atalaya.toolbox.youtube.playlist.repository.YoutubePlaylistMetadataProvider;
import com.atalaya.toolbox.youtube.history.repository.InaccessibleVideoMetadataRepository;
import com.atalaya.toolbox.youtube.download.domain.DownloadedTrack;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jfposton.ytdlp.DownloadProgressCallback;
import com.jfposton.ytdlp.StreamOutputCallback;
import com.jfposton.ytdlp.YtDlp;
import com.jfposton.ytdlp.YtDlpException;
import com.jfposton.ytdlp.YtDlpRequest;
import com.jfposton.ytdlp.YtDlpResponse;
import com.jfposton.ytdlp.utils.StreamGobbler;
import com.jfposton.ytdlp.utils.StreamProcessExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class YtDlpClient implements YoutubeMediaDownloader, YoutubePlaylistMetadataProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(YtDlpClient.class);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern VIDEO_ID_JSON_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");
    private static final ScheduledExecutorService PROGRESS_LOGGER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "youtube-download-progress");
        thread.setDaemon(true);
        return thread;
    });

    private final YoutubeProperties properties;
    private final ObjectMapper objectMapper;
    private final InaccessibleVideoMetadataRepository inaccessibleMetadataRepository;

    public YtDlpClient(YoutubeProperties properties, ObjectMapper objectMapper, InaccessibleVideoMetadataRepository inaccessibleMetadataRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.inaccessibleMetadataRepository = inaccessibleMetadataRepository;
    }

    @Override
    public List<DownloadedTrack> download(String url) {
        return download(url, null);
    }

    @Override
    public List<DownloadedTrack> download(String url, String destination) {
        Path storageDirectory = properties.resolvedStorageDirectory();
        Path downloadsDirectory = properties.resolvedMusicDirectory(destination);
        Path workDirectory = storageDirectory.resolve("work");
        try {
            LOGGER.info("Preparing YouTube download workspace for {}", url);
            Files.createDirectories(downloadsDirectory);
            Files.createDirectories(workDirectory);
            try (DownloadWorkspace workspace = DownloadWorkspace.create(workDirectory)) {
                List<PreparedTrack> videos = fetchMetadata(url);
                LOGGER.info("Found {} track(s) to download from {}", videos.size(), url);
                return downloadTracks(videos, workspace.path(), downloadsDirectory);
            }
        } catch (IOException e) {
            LOGGER.error("Could not prepare or clean up the YouTube download workspace for {}", url, e);
            throw new YoutubeDownloadException("No se pudo guardar la descarga de YouTube.", e);
        }
    }

    public static List<String> customBuildArguments(YtDlpRequest request) {
        List<String> arguments = new ArrayList<>();
        if (request.getUrl() != null) {
            arguments.add(request.getUrl());
        }
        Iterator<Map.Entry<String, String>> it = request.getOption().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> option = it.next();
            String value = option.getValue();
            arguments.add("--" + option.getKey());
            if (value != null && !value.isEmpty()) {
                arguments.add(value);
            }
            it.remove();
        }
        return arguments;
    }

    public static YtDlpResponse customExecute(YtDlpRequest request, DownloadProgressCallback progressCallback, StreamOutputCallback stdOutCallback, StreamOutputCallback errOutCallback) throws YtDlpException {
        List<String> commandArguments = new ArrayList<>();
        commandArguments.add(YtDlp.getExecutablePath());
        commandArguments.addAll(customBuildArguments(request));
        String command = String.join(" ", commandArguments);
        String directory = request.getDirectory();
        Map<String, String> options = request.getOption();
        YtDlpResponse ytDlpResponse;
        Process process;
        int exitCode;
        StringBuilder outBuffer = new StringBuilder(); // stdout
        StringBuilder errBuffer = new StringBuilder(); // stderr
        long startTime = System.nanoTime();
        ProcessBuilder processBuilder = new ProcessBuilder(commandArguments);
        if (directory != null) {
            processBuilder.directory(new File(directory));
        }
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new YtDlpException(e);
        }
        InputStream outStream = process.getInputStream();
        InputStream errStream = process.getErrorStream();
        StreamProcessExtractor stdOutProcessor = new StreamProcessExtractor(outBuffer, outStream, progressCallback, stdOutCallback);
        StreamGobbler stdErrProcessor = new StreamGobbler(errBuffer, errStream, errOutCallback);
        try {
            stdOutProcessor.join();
            stdErrProcessor.join();
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new YtDlpException(e);
        }
        String out = outBuffer.toString();
        String err = errBuffer.toString();
        if (exitCode > 1) {
            throw new YtDlpException(err);
        }
        int elapsedTime = (int) ((System.nanoTime() - startTime) / 1000000);
        ytDlpResponse = new YtDlpResponse(command, options, directory, exitCode, elapsedTime, out, err);
        return ytDlpResponse;
    }

    @Override
    public List<PreparedTrack> fetchMetadata(String url) {
        LOGGER.info("Reading video metadata from {}", url);
        Map<String, PreparedTrack> videosById = new LinkedHashMap<>();
        String output = executeMetadataRequest(url, false);
        for (String line : rawLines(output)) {
            PreparedTrack track = parseMetadataTrack(line, url);
            if (track != null) {
                videosById.putIfAbsent(track.videoId(), track);
            }
        }
        if (isPlaylistUrl(url)) {
            String flatOutput = executeMetadataRequest(url, true);
            for (String videoId : flatPlaylistVideoIds(flatOutput, url)) {
                if (videosById.containsKey(videoId)) {
                    continue;
                }
                String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
                String videoOutput = executeMetadataRequest(videoUrl, false);
                PreparedTrack track = rawLines(videoOutput).stream()
                        .map(line -> parseMetadataTrack(line, url))
                        .filter(java.util.Objects::nonNull)
                        .filter(candidate -> candidate.videoId().equals(videoId))
                        .findFirst()
                        .orElse(null);
                if (track == null) {
                    inaccessibleMetadataRepository.recordFailure(videoId, url, "yt-dlp no pudo obtener metadata detallada para este video.");
                } else {
                    videosById.put(videoId, track);
                    inaccessibleMetadataRepository.recordSuccess(videoId);
                }
            }
        }
        return List.copyOf(videosById.values());
    }

    private String executeMetadataRequest(String url, boolean flatPlaylist) {
        YtDlpRequest request = new YtDlpRequest(url);
        request.setOption("dump-json");
        request.setOption("ignore-errors");
        if (flatPlaylist) {
            request.setOption("flat-playlist");
        }
        configureEjs(request, properties);
        long startedAt = System.nanoTime();
        ScheduledFuture<?> progressReminder = scheduleMetadataProgressReminder(url, startedAt);
        try {
            return customExecute(request, null, null, null).getOut();
        } catch (YtDlpException e) {
            LOGGER.warn("Failed to read video metadata from {}: {}", url, e.getMessage());
            return "";
        } finally {
            progressReminder.cancel(false);
        }
    }

    private List<String> rawLines(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return output.lines().filter(value -> !value.isBlank()).toList();
    }

    private PreparedTrack parseMetadataTrack(String line, String sourceUrl) {
        try {
            Map<String, Object> metadata = objectMapper.readValue(line, new TypeReference<>() {});
            String videoId = metadataText(metadata, "id");
            String title = metadataText(metadata, "title");
            if (videoId.isBlank()) {
                return null;
            }
            if (!VIDEO_ID_PATTERN.matcher(videoId).matches() || title.isBlank()) {
                LOGGER.warn("Skipping incomplete yt-dlp metadata entry with video ID '{}'", videoId);
                inaccessibleMetadataRepository.recordFailure(videoId, sourceUrl, "El resultado JSON no incluyó un ID válido o el título.");
                return null;
            }
            String videoUrl = metadataText(metadata, "webpage_url");
            if (videoUrl.isBlank()) {
                videoUrl = metadataText(metadata, "original_url");
            }
            if (videoUrl.isBlank()) {
                videoUrl = "https://www.youtube.com/watch?v=" + videoId;
            }
            return new PreparedTrack(videoId, title, videoUrl, new LinkedHashMap<>(metadata));
        } catch (IOException e) {
            java.util.regex.Matcher idMatcher = VIDEO_ID_JSON_PATTERN.matcher(line);
            if (idMatcher.find()) {
                inaccessibleMetadataRepository.recordFailure(idMatcher.group(1), sourceUrl, "yt-dlp devolvió JSON de metadata ilegible.");
            }
            LOGGER.warn("Could not parse one yt-dlp metadata entry: {}", e.getMessage());
            return null;
        }
    }

    private List<String> flatPlaylistVideoIds(String output, String sourceUrl) {
        Set<String> videoIds = new LinkedHashSet<>();
        if (output == null || output.isBlank()) {
            return List.of();
        }
        for (String line : output.lines().filter(value -> !value.isBlank()).toList()) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(line, new TypeReference<>() {});
                String videoId = metadataText(metadata, "id");
                if (VIDEO_ID_PATTERN.matcher(videoId).matches()) {
                    videoIds.add(videoId);
                }
            } catch (IOException e) {
                Matcher idMatcher = VIDEO_ID_JSON_PATTERN.matcher(line);
                if (idMatcher.find()) {
                    inaccessibleMetadataRepository.recordFailure(idMatcher.group(1), sourceUrl, "yt-dlp devolvió una entrada plana ilegible.");
                }
                LOGGER.warn("Could not parse flat playlist entry: {}", e.getMessage());
            }
        }
        return List.copyOf(videoIds);
    }

    private boolean isPlaylistUrl(String url) {
        try {
            String query = java.net.URI.create(url).getRawQuery();
            return (query != null && java.util.Arrays.stream(query.split("&")).anyMatch(parameter -> parameter.startsWith("list="))) || java.net.URI.create(url).getPath().contains("/playlist");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<DownloadedTrack> downloadTracks(List<PreparedTrack> videos, Path workspace, Path downloadsDirectory) throws IOException {
        List<DownloadedTrack> tracks = new ArrayList<>();
        for (PreparedTrack video : videos) {
            String videoId = video.videoId();
            if (videoId == null || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
                continue;
            }
            String title = video.name();
            if (title == null || title.isBlank()) {
                throw new YoutubeDownloadException("Falta el título en los metadatos del vídeo " + videoId + ".");
            }
            String videoUrl = video.url();
            if (videoUrl == null || videoUrl.isBlank()) {
                videoUrl = "https://www.youtube.com/watch?v=" + videoId;
            }
            LOGGER.info("Starting download for '{}' ({})", title, videoId);
            long startedAt = System.nanoTime();
            ScheduledFuture<?> progressReminder = scheduleProgressReminder(title, videoId, startedAt);
            YtDlpResponse response;
            try {
                response = executeYtDlp(videoUrl, workspace);
            } finally {
                progressReminder.cancel(false);
            }
            Path downloadedMp3 = workspace.resolve(videoId + ".mp3");
            if (!Files.isRegularFile(downloadedMp3)) {
                LOGGER.warn("yt-dlp completed without producing an MP3 for '{}' ({})", title, videoId);
                continue;
            }
            String filename = filenameFor(title, videoId);
            Path finalFile = downloadsDirectory.resolve(filename);
            Files.move(downloadedMp3, finalFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Finished '{}' in {} ms; saved to {}", title, response.getElapsedTime(), finalFile);
            tracks.add(new DownloadedTrack(videoId, title, videoUrl, finalFile.toString(), response.getElapsedTime()));
        }
        return tracks;
    }

    private String metadataText(Map<String, Object> metadata, String field) {
        Object value = metadata.get(field);
        return value == null ? "" : value.toString();
    }

    private ScheduledFuture<?> scheduleProgressReminder(String title, String videoId, long startedAt) {
        return PROGRESS_LOGGER.scheduleAtFixedRate(() -> {
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
            LOGGER.info("Still downloading '{}' ({}) after {} seconds", title, videoId, elapsedSeconds);
        }, 10, 10, TimeUnit.SECONDS);
    }

    private ScheduledFuture<?> scheduleMetadataProgressReminder(String url, long startedAt) {
        return PROGRESS_LOGGER.scheduleAtFixedRate(() -> {
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
            LOGGER.info("Still retrieving playlist metadata from {} after {} seconds", url, elapsedSeconds);
        }, 10, 10, TimeUnit.SECONDS);
    }

    public static String filenameFor(String title, String videoId) {
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[. ]+$", "").strip();
        if (safeTitle.isBlank() || safeTitle.equals(".") || safeTitle.equals("..")) {
            safeTitle = "youtube-track";
        }
        return safeTitle + " --- " + videoId + ".mp3";
    }

    private YtDlpResponse executeYtDlp(String url, Path destination) {
        YtDlpRequest request = new YtDlpRequest(url, destination.toString());
        request.setOption("format", "bestaudio/best");
        request.setOption("extract-audio");
        request.setOption("audio-format", "mp3");
        request.setOption("audio-quality", "0");
        request.setOption("output", "%(id)s.%(ext)s");
        configureEjs(request, properties);
        try {
            return YtDlp.execute(request);
        } catch (YtDlpException e) {
            LOGGER.error("yt-dlp failed for {}", url, e);
            throw new YoutubeDownloadException("yt-dlp no pudo completar la descarga: " + e.getMessage(), e);
        }
    }

    public static void configureEjs(YtDlpRequest request, YoutubeProperties properties) {
        request.setOption("js-runtimes", properties.resolvedJsRuntime());
        request.setOption("remote-components", properties.resolvedEjsRemoteComponents());
    }

    private record DownloadWorkspace(Path path) implements AutoCloseable {
        private static DownloadWorkspace create(Path parent) throws IOException {
            Path workspace = Files.createTempDirectory(parent, "download-");
            LOGGER.debug("Created temporary download workspace {}", workspace);
            return new DownloadWorkspace(workspace);
        }

        @Override
        public void close() throws IOException {
            try (Stream<Path> paths = Files.walk(path)) {
                for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            LOGGER.debug("Removed temporary download workspace {}", path);
        }
    }
}