package dev.p2plab.payments.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class CapabilitiesController {
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("service", "p2p-payments-lab", "paymentProcessingEnabled", false, "chapter", 1);
    }
}
