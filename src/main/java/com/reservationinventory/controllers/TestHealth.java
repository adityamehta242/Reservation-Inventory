package com.reservationinventory.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TestHealth {

    @GetMapping("/test")
    public String test() {
        return "Hello, this is working!";  // ✅ Will be returned as plain text/JSON
    }
}
