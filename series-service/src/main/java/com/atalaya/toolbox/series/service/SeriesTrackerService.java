package com.atalaya.toolbox.series.service;

import com.atalaya.toolbox.series.domain.EpisodeWatchRequest;
import com.atalaya.toolbox.series.domain.SeriesProfile;
import com.atalaya.toolbox.series.domain.SeriesShow;
import com.atalaya.toolbox.series.domain.SeriesShowRequest;
import com.atalaya.toolbox.series.domain.WatchedEpisode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.Iterator;
import java.util.regex.Pattern;

@Service
public class SeriesTrackerService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final List<String> STATUSES = List.of("AIRING", "HIATUS", "COMPLETED", "PLANNED", "DROPPED");
    private static final List<String> DAYS = List.of(
        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "FLEXIBLE"
    );
    private static final int MAX_COVER_BYTES = 5 * 1024 * 1024;
    private static final long MAX_COVER_PIXELS = 40_000_000;

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;

    public SeriesTrackerService(
        ObjectMapper objectMapper,
        @Value("${toolbox.series.storage-directory:../.data/series}") String storageDirectory
    ) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(System.getProperty("user.dir")).resolve(storageDirectory).normalize();
    }

    public synchronized List<SeriesShow> listShows(String requestedUsername) {
        return ensureProfile(requestedUsername).shows().stream()
            .sorted(Comparator.comparing(SeriesShow::title, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public synchronized SeriesShow createShow(String username, SeriesShowRequest request) {
        validateRequest(request);
        SeriesProfile profile = requireProfile(username);
        Instant now = Instant.now();
        SeriesShow show = new SeriesShow(
            UUID.randomUUID().toString(), clean(request.title()), clean(request.description()),
            normalizeStatus(request.status()), clean(request.platform()), cleanGenres(request.genres()),
            normalizeDay(request.scheduleDay()), clean(request.scheduleTime()),
            request.timezone() == null || request.timezone().isBlank() ? "UTC" : request.timezone().trim(),
            request.seasons(), request.episodesPerSeason(), List.of(), clean(request.nextEpisodeDate()),
            request.rating(), clean(request.startDate()), clean(request.finishDate()), null,
            clean(request.notes()), now.toString(), now.toString()
        );
        List<SeriesShow> shows = new ArrayList<>(profile.shows());
        shows.add(show);
        saveProfile(new SeriesProfile(profile.username(), shows));
        return show;
    }

    public synchronized SeriesShow updateShow(String username, String id, SeriesShowRequest request) {
        validateRequest(request);
        SeriesProfile profile = requireProfile(username);
        SeriesShow existing = requireShow(profile, id);
        SeriesShow updated = new SeriesShow(
            existing.id(), clean(request.title()), clean(request.description()), normalizeStatus(request.status()),
            clean(request.platform()), cleanGenres(request.genres()), normalizeDay(request.scheduleDay()),
            clean(request.scheduleTime()), request.timezone() == null || request.timezone().isBlank()
                ? "UTC" : request.timezone().trim(),
            request.seasons(), request.episodesPerSeason(), existing.watchedEpisodes(),
            clean(request.nextEpisodeDate()), request.rating(), clean(request.startDate()),
            clean(request.finishDate()), existing.coverUrl(), clean(request.notes()), existing.createdAt(),
            Instant.now().toString()
        );
        replaceShow(profile, updated);
        return updated;
    }

    public synchronized void deleteShow(String username, String id) {
        SeriesProfile profile = requireProfile(username);
        SeriesShow show = requireShow(profile, id);
        List<SeriesShow> shows = profile.shows().stream().filter(item -> !item.id().equals(id)).toList();
        saveProfile(new SeriesProfile(profile.username(), shows));
        deleteCover(show.coverUrl());
    }

    public synchronized SeriesShow updateEpisode(String username, String id, EpisodeWatchRequest request) {
        if ((request.previousSeason() == null) != (request.previousEpisode() == null)) {
            throw new IllegalArgumentException("Previous season and episode must be provided together.");
        }
        SeriesProfile profile = requireProfile(username);
        SeriesShow show = requireShow(profile, id);
        List<WatchedEpisode> episodes = new ArrayList<>(show.watchedEpisodes());
        int previousSeason = request.previousSeason() == null ? request.season() : request.previousSeason();
        int previousEpisode = request.previousEpisode() == null ? request.episode() : request.previousEpisode();
        WatchedEpisode previous = episodes.stream()
            .filter(item -> item.season() == previousSeason && item.episode() == previousEpisode)
            .findFirst()
            .orElse(null);
        if ((request.previousSeason() != null || request.previousEpisode() != null) && previous == null) {
            throw new NoSuchElementException("Watched episode not found.");
        }
        boolean sameEpisode = previousSeason == request.season() && previousEpisode == request.episode();
        if (request.watched() && !sameEpisode && episodes.stream()
            .anyMatch(item -> item.season() == request.season() && item.episode() == request.episode())) {
            throw new IllegalArgumentException("That episode is already in your watch log.");
        }
        episodes.removeIf(item -> item.season() == previousSeason && item.episode() == previousEpisode);
        if (request.watched()) {
            episodes.add(new WatchedEpisode(
                request.season(), request.episode(), clean(request.title()),
                previous == null ? Instant.now().toString() : previous.watchedAt(), clean(request.notes())
            ));
        }
        episodes.sort(Comparator.comparingInt(WatchedEpisode::season).thenComparingInt(WatchedEpisode::episode));
        SeriesShow updated = withEpisodes(show, episodes);
        replaceShow(profile, updated);
        return updated;
    }

    public synchronized SeriesShow uploadCover(String username, String id, MultipartFile image) {
        SeriesProfile profile = requireProfile(username);
        SeriesShow show = requireShow(profile, id);
        if (image.isEmpty() || image.getSize() > MAX_COVER_BYTES) {
            throw new IllegalArgumentException("Cover image must be between 1 byte and 5 MB.");
        }
        String extension = validatedImageExtension(image);
        String filename = UUID.randomUUID() + extension;
        Path coversDirectory = storageDirectory.resolve("covers");
        Path destination = coversDirectory.resolve(filename);
        Path temporary = coversDirectory.resolve(filename + ".tmp");
        try {
            Files.createDirectories(coversDirectory);
            Files.write(temporary, image.getBytes());
            moveAtomically(temporary, destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save the series cover image.", exception);
        }

        SeriesShow updated = withCover(show, "/api/series/covers/" + filename);
        try {
            replaceShow(profile, updated);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
        deleteCover(show.coverUrl());
        return updated;
    }

    public Path coverPath(String fileName) {
        if (fileName == null || !fileName.matches("[a-f0-9-]{36}\\.(jpg|png|webp)")) {
            throw new NoSuchElementException("Cover image not found.");
        }
        Path path = storageDirectory.resolve("covers").resolve(fileName).normalize();
        if (!path.startsWith(storageDirectory.resolve("covers")) || !Files.isRegularFile(path)) {
            throw new NoSuchElementException("Cover image not found.");
        }
        return path;
    }

    private SeriesShow withEpisodes(SeriesShow show, List<WatchedEpisode> episodes) {
        return new SeriesShow(show.id(), show.title(), show.description(), show.status(), show.platform(),
            show.genres(), show.scheduleDay(), show.scheduleTime(), show.timezone(), show.seasons(),
            show.episodesPerSeason(), episodes, show.nextEpisodeDate(), show.rating(), show.startDate(),
            show.finishDate(), show.coverUrl(), show.notes(), show.createdAt(), Instant.now().toString());
    }

    private SeriesShow withCover(SeriesShow show, String coverUrl) {
        return new SeriesShow(show.id(), show.title(), show.description(), show.status(), show.platform(),
            show.genres(), show.scheduleDay(), show.scheduleTime(), show.timezone(), show.seasons(),
            show.episodesPerSeason(), show.watchedEpisodes(), show.nextEpisodeDate(), show.rating(),
            show.startDate(), show.finishDate(), coverUrl, show.notes(), show.createdAt(), Instant.now().toString());
    }

    private void replaceShow(SeriesProfile profile, SeriesShow updated) {
        List<SeriesShow> shows = new ArrayList<>(profile.shows());
        int index = indexOfShow(shows, updated.id());
        shows.set(index, updated);
        saveProfile(new SeriesProfile(profile.username(), shows));
    }

    private void validateRequest(SeriesShowRequest request) {
        normalizeStatus(request.status());
        normalizeDay(request.scheduleDay());
        if (request.scheduleTime() != null && !request.scheduleTime().isBlank()) {
            try {
                LocalTime.parse(request.scheduleTime());
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Schedule time must use HH:mm format.");
            }
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            try {
                ZoneId.of(request.timezone());
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("Timezone must be a valid IANA timezone.");
            }
        }
        validateDate(request.nextEpisodeDate(), "Next episode date");
        validateDate(request.startDate(), "Start date");
        validateDate(request.finishDate(), "Finish date");
        if (request.rating() != null && (request.rating() < 0 || request.rating() > 10)) {
            throw new IllegalArgumentException("Rating must be between 0 and 10.");
        }
    }

    private void validateDate(String date, String label) {
        if (date == null || date.isBlank()) return;
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(label + " must use YYYY-MM-DD format.");
        }
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Status must be AIRING, HIATUS, COMPLETED, PLANNED, or DROPPED.");
        }
        return normalized;
    }

    private String normalizeDay(String day) {
        String normalized = day == null ? "" : day.trim().toUpperCase(Locale.ROOT);
        if (!DAYS.contains(normalized)) throw new IllegalArgumentException("Schedule day must be a weekday or FLEXIBLE.");
        return normalized;
    }

    private List<String> cleanGenres(List<String> genres) {
        if (genres == null) return List.of();
        return genres.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private String validatedImageExtension(MultipartFile image) {
        String contentType = image.getContentType();
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the uploaded cover image.", exception);
        }
        boolean jpeg = "image/jpeg".equalsIgnoreCase(contentType) && bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = "image/png".equalsIgnoreCase(contentType) && bytes.length >= 8
            && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
            && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        boolean webp = isWebpImage(bytes) && "image/webp".equalsIgnoreCase(contentType);
        if (jpeg && isReadableRaster(bytes)) return ".jpg";
        if (png && isReadableRaster(bytes)) return ".png";
        if (webp && webpDimensionsAreSafe(bytes)) return ".webp";
        throw new IllegalArgumentException("Cover must be a valid JPEG, PNG, or WebP image.");
    }

    private boolean isReadableRaster(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 && (long) width * height <= MAX_COVER_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean isWebpImage(byte[] bytes) {
        if (bytes.length < 30 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
            || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') return false;
        long declaredLength = unsignedLittleEndian(bytes, 4, 4);
        long chunkLength = unsignedLittleEndian(bytes, 16, 4);
        if (declaredLength != bytes.length - 8L || chunkLength > bytes.length - 20L || chunkLength == 0) return false;
        boolean lossy = bytes[12] == 'V' && bytes[13] == 'P' && bytes[14] == '8' && bytes[15] == ' ';
        boolean lossless = bytes[12] == 'V' && bytes[13] == 'P' && bytes[14] == '8' && bytes[15] == 'L';
        boolean extended = bytes[12] == 'V' && bytes[13] == 'P' && bytes[14] == '8' && bytes[15] == 'X';
        if (!lossy && !lossless && !extended) return false;
        if (lossy) return chunkLength >= 10 && bytes[23] == (byte) 0x9d && bytes[24] == 0x01 && bytes[25] == 0x2a;
        if (lossless) return chunkLength >= 5 && bytes[20] == 0x2f;
        return chunkLength >= 10;
    }

    private boolean webpDimensionsAreSafe(byte[] bytes) {
        long width;
        long height;
        if (bytes[15] == 'X') {
            width = 1 + unsignedLittleEndian(bytes, 24, 3);
            height = 1 + unsignedLittleEndian(bytes, 27, 3);
        } else if (bytes[15] == 'L') {
            long dimensions = unsignedLittleEndian(bytes, 21, 4);
            width = 1 + (dimensions & 0x3fff);
            height = 1 + ((dimensions >>> 14) & 0x3fff);
        } else {
            width = unsignedLittleEndian(bytes, 26, 2) & 0x3fff;
            height = unsignedLittleEndian(bytes, 28, 2) & 0x3fff;
        }
        return width > 0 && height > 0 && width * height <= MAX_COVER_PIXELS;
    }

    private long unsignedLittleEndian(byte[] bytes, int start, int length) {
        long value = 0;
        for (int offset = 0; offset < length; offset++) {
            value |= (long) (bytes[start + offset] & 0xff) << (8 * offset);
        }
        return value;
    }

    private SeriesProfile requireProfile(String requestedUsername) {
        String username = normalizeUsername(requestedUsername);
        Path path = profilePath(username);
        if (!Files.exists(path)) {
            Path legacyPath = storageDirectory.resolve("users").resolve(username + ".json");
            if (Files.exists(legacyPath)) {
                SeriesProfile legacyProfile = readProfile(legacyPath);
                if (!username.equals(legacyProfile.username())) {
                    throw new IllegalStateException("Legacy series profile filename does not match its username.");
                }
                saveProfile(legacyProfile);
                removeMigratedLegacyProfile(legacyPath);
                return legacyProfile;
            }
            SeriesProfile empty = new SeriesProfile(username, List.of());
            saveProfile(empty);
            return empty;
        }
        SeriesProfile profile = readProfile(path);
        if (!profile.username().equals(username)) throw new IllegalStateException("Series profile filename does not match its username.");
        return profile;
    }

    private SeriesProfile ensureProfile(String requestedUsername) {
        return requireProfile(requestedUsername);
    }

    private SeriesShow requireShow(SeriesProfile profile, String id) {
        return profile.shows().stream().filter(show -> show.id().equals(id)).findFirst()
            .orElseThrow(() -> new NoSuchElementException("Series not found."));
    }

    private int indexOfShow(List<SeriesShow> shows, String id) {
        for (int index = 0; index < shows.size(); index++) {
            if (shows.get(index).id().equals(id)) return index;
        }
        throw new NoSuchElementException("Series not found.");
    }

    private SeriesProfile readProfile(Path path) {
        try {
            SeriesProfile profile = objectMapper.readValue(path.toFile(), SeriesProfile.class);
            return new SeriesProfile(profile.username(), profile.shows() == null ? List.of() : profile.shows());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read series tracker profile " + path, exception);
        }
    }

    private void saveProfile(SeriesProfile profile) {
        Path path = profilePath(profile.username());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(temporary.toFile(), profile);
            moveAtomically(temporary, path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save series tracker profile " + profile.username(), exception);
        }
    }

    private void removeMigratedLegacyProfile(Path legacyPath) {
        try {
            Files.delete(legacyPath);
            Path legacyDirectory = legacyPath.getParent();
            try (var files = Files.list(legacyDirectory)) {
                if (files.findAny().isEmpty()) Files.delete(legacyDirectory);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not retire the migrated legacy series profile " + legacyPath, exception);
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteCover(String coverUrl) {
        if (coverUrl == null || !coverUrl.startsWith("/api/series/covers/")) return;
        try {
            String fileName = coverUrl.substring("/api/series/covers/".length());
            if (fileName.matches("[a-f0-9-]{36}\\.(jpg|png|webp)")) {
                Files.deleteIfExists(storageDirectory.resolve("covers").resolve(fileName).normalize());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not remove the old series cover image.", exception);
        }
    }

    private Path profilePath(String username) {
        return storageDirectory.resolve(username + ".json");
    }

    private String normalizeUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must be 1-32 letters, numbers, underscores, or hyphens.");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
