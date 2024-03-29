package ru.maipomogator.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ru.maipomogator.model.Lesson;
import ru.maipomogator.repo.LessonRepo;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LessonService {
    private final LessonRepo lessonRepo;

    public Optional<Lesson> findById(Long id) {
        return lessonRepo.findById(id);
    }

    public List<Lesson> findAll() {
        return lessonRepo.findAllLazy();
    }

    public List<Lesson> findAllEager() {
        return lessonRepo.findAll();
    }

    @Transactional
    public Lesson save(Lesson lesson) {
        return lessonRepo.save(lesson);
    }

    @Transactional
    public void saveAll(Iterable<Lesson> lessons) {
        lessonRepo.saveAll(lessons);
    }

    @Transactional
    public void delete(Long id) {
        lessonRepo.deleteById(id);
    }

    public List<Lesson> findForGroupBetweenDates(LocalDate startDate, LocalDate endDate, Long groupId) {
        return lessonRepo.findByDateBetweenAndGroupsId(startDate, endDate, groupId);
    }

    public List<Lesson> findForProfessorBetweenDates(LocalDate startDate, LocalDate endDate, Long professorId) {
        return lessonRepo.findByDateBetweenAndProfessorsId(startDate, endDate, professorId);
    }
}
