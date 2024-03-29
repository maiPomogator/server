package ru.maipomogator.parser.mai;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Future.State;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import ru.maipomogator.model.Group;
import ru.maipomogator.model.Lesson;
import ru.maipomogator.model.Professor;
import ru.maipomogator.parser.adapters.GroupListAdapter;
import ru.maipomogator.parser.adapters.ParsedGroup;
import ru.maipomogator.parser.adapters.ParsedGroupAdapter;
import ru.maipomogator.parser.tasks.DownloadFileTask;
import ru.maipomogator.parser.tasks.ParseGroupTask;

@Log4j2
@Component
public class MaiParser {
    private static final String BASE_URL = "https://public.mai.ru/schedule/data/";
    private static final String GROUPS_JSON_FILENAME = "groups.json";

    private final Gson gson;
    private final Path basePath;
    private final Path groupsFilesBasePath;

    @SneakyThrows
    public MaiParser() {
        Path basePathStarter = Files.createTempDirectory("mai");
        this.basePath = basePathStarter.resolve(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        this.groupsFilesBasePath = this.basePath.resolve("groups");
        this.gson = getCustomGson();

        Files.createDirectories(groupsFilesBasePath);
    }

    public MaiTimetable getTimetable() {
        LocalTime timeStart = LocalTime.now();
        Map<String, Group> rawGroups = getRawGroups();
        List<ParsedGroup> parsedGroups = getParsedGroups(rawGroups.values());
        log.info("Raw number of lessons: {} ", parsedGroups.stream().mapToInt(gr -> gr.getLessons().size()).sum());
        MaiTimetable maiTimetable = processParsedGroups(parsedGroups, rawGroups);

        log.info("Successfully processed timetable.");
        log.info("Time spent: {} seconds", timeStart.until(LocalTime.now(), java.time.temporal.ChronoUnit.SECONDS));
        log.info("Number of groups: {}", maiTimetable.getGroups().size());
        log.info("Number of professors: {}", maiTimetable.getProfessors().size());
        log.info("Number of lessons: {}", maiTimetable.getLessons().size());

        return maiTimetable;
    }

    private MaiTimetable processParsedGroups(List<ParsedGroup> parsedGroups, Map<String, Group> rawGroups) {
        Map<Long, Lesson> allLessons = new HashMap<>();
        Map<UUID, Professor> allProfessors = new HashMap<>();
        Map<String, Group> allGroups = new HashMap<>();
        for (final ParsedGroup parsedGroup : parsedGroups) {
            Group targetGroup = rawGroups.get(parsedGroup.getGroupName());

            for (final Lesson newLesson : parsedGroup.getLessons()) {
                Lesson targetLesson = allLessons.computeIfAbsent(newLesson.getHash(),
                        hash -> Lesson.copyOf(newLesson));
                targetLesson.addGroup(targetGroup);

                for (final Professor newProfessor : newLesson.getProfessors()) {
                    Professor targetProfessor = allProfessors.computeIfAbsent(newProfessor.getSiteId(),
                            uuid -> Professor.copyOf(newProfessor));
                    targetLesson.addProfessor(targetProfessor);
                }
            }
            allGroups.put(targetGroup.getName(), targetGroup);
        }
        return new MaiTimetable(allLessons, allProfessors, allGroups);
    }

    private Map<String, Group> getRawGroups() {
        File groupsFile = getGroupsFile();
        try (Reader groupsReader = Files.newBufferedReader(groupsFile.toPath())) {
            List<Group> groupList = gson.fromJson(groupsReader, new TypeToken<List<Group>>() {});
            log.info("Got {} groups from general file.", groupList.size());
            return groupList.stream().collect(Collectors.toMap(Group::getName, gr -> gr));
        } catch (IOException e) {
            throw new IllegalStateException("IO error");
        }
    }

    private File getGroupsFile() {
        Path groupsJsonPath = basePath.resolve(GROUPS_JSON_FILENAME);
        File groupsJson = groupsJsonPath.toFile();
        if (groupsJson.isFile()) {
            return groupsJson;
        }

        log.debug("Actual {} wasn`t found. Downloading new.", GROUPS_JSON_FILENAME);
        try {
            DownloadFileTask task = new DownloadFileTask(BASE_URL + GROUPS_JSON_FILENAME, groupsJsonPath);
            return task.call();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Wrong URL for %s".formatted(GROUPS_JSON_FILENAME), e);
        } catch (IOException e) {
            throw new IllegalStateException("IO error");
        }
    }

    private List<ParsedGroup> getParsedGroups(Collection<Group> rawGroups) {
        List<File> groupFiles = getGroupFiles(rawGroups);
        try (ExecutorService parserService = Executors.newFixedThreadPool(10)) {
            log.info("Starting parsing of {} groups", groupFiles.size());
            List<Future<ParsedGroup>> futures = parserService.invokeAll(groupFiles.stream()
                    .map(file -> new ParseGroupTask(file.toPath(), gson)).toList()/* , 5, TimeUnit.MINUTES */);
            log.info("Parsing succeeded for {} files",
                    futures.stream().filter(f -> f.state().equals(State.SUCCESS)).count());
            log.info("Parsing failed for {} files",
                    futures.stream().filter(f -> f.state().equals(State.FAILED)).count());
            futures.stream().filter(f -> f.state().equals(State.FAILED))
                    .forEach(future -> System.out.println(future.exceptionNow().getMessage()));
            return futures.stream().filter(f -> f.state().equals(State.SUCCESS)).map(Future::resultNow).toList();
        } catch (InterruptedException e) {
            log.error("Interrupted during parsing lessons. {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    private List<File> getGroupFiles(Collection<Group> rawGroups) {
        List<File> groupFiles = new ArrayList<>(rawGroups.size());
        List<DownloadFileTask> filesToDownload = new ArrayList<>();
        for (Group group : rawGroups) {
            String fileName = group.getMd5OfName() + ".json";
            Path groupFilePath = groupsFilesBasePath.resolve(fileName);
            File groupFile = groupFilePath.toFile();
            if (groupFile.isFile()) {
                groupFiles.add(groupFile);
            } else {
                filesToDownload.add(new DownloadFileTask(BASE_URL + fileName, groupFilePath));
            }
        }
        if (filesToDownload.isEmpty()) {
            log.info("All {} groups has local files.", groupFiles.size());
            return groupFiles;
        }

        try (ExecutorService downloadService = Executors.newFixedThreadPool(5)) {
            log.info("Downloading {} files", filesToDownload.size());
            List<Future<File>> futures = downloadService.invokeAll(filesToDownload);
            // TODO Добавить удаление старых файлов
            log.info("Download succeeded for {} files",
                    futures.stream().filter(f -> f.state().equals(State.SUCCESS)).count());
            log.info("Download failed for {} files",
                    futures.stream().filter(f -> f.state().equals(State.FAILED)).count());
            futures.stream().filter(f -> f.state().equals(State.FAILED)).forEach(Future::exceptionNow);
            groupFiles.addAll(
                    futures.stream().filter(f -> f.state().equals(State.SUCCESS)).map(Future::resultNow).toList());
        } catch (InterruptedException e) {
            log.error("Interrupted during downloading group files. {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
        log.info("Returning {} files for {} groups", groupFiles.size(), rawGroups.size());
        return groupFiles;
    }

    private Gson getCustomGson() {
        GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(new TypeToken<List<Group>>() {}.getType(), new GroupListAdapter())
                .registerTypeAdapter(new TypeToken<ParsedGroup>() {}.getType(), new ParsedGroupAdapter());
        return builder.create();
    }
}
