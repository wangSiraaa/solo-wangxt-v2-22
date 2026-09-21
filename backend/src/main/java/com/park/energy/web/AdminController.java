package com.park.energy.web;

import com.park.energy.service.SeedService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SeedService seedService;

    public AdminController(SeedService seedService) {
        this.seedService = seedService;
    }

    /** 初始化样本费率/表计（幂等，可反复调用）。 */
    @PostMapping("/seed")
    public Map<String, String> seed() {
        seedService.seed();
        return Map.of("result", "seeded (idempotent)");
    }
}
