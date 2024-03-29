package ru.maipomogator.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ru.maipomogator.parser.mai.MaiInfo;
import ru.maipomogator.service.GroupService;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class MaiController {
    private final GroupService groupService;

    @GetMapping("/mai")
    public MaiInfo getInfo() {
        List<String> faculties = groupService.findAllFaculties();
        Integer numberOfCourses = groupService.getNumberOfCourses();
        return new MaiInfo(faculties, numberOfCourses);
    }
}
