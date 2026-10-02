package com.cecil.credchain.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** When the React build is bundled into the jar, forward client-side routes to index.html. */
@Controller
public class SpaController {

    @GetMapping({"/", "/issue", "/verify", "/verify/{id}", "/explorer", "/explorer/{index}",
            "/institutions", "/credential/{id}", "/student"})
    public String forward() {
        return "forward:/index.html";
    }
}
