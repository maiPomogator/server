package ru.maipomogator.repo;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import ru.maipomogator.model.Lesson;

@Repository
public interface LessonRepo extends JpaRepository<Lesson, Long> {

    @Query("select l from Lesson l")
    List<Lesson> findAllLazy();

    @EntityGraph(attributePaths = { "types", "rooms", "professors", "groups" })
    List<Lesson> findAll();

}
