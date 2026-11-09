package dev.p2plab.payments.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CapabilitiesController.class)
class CapabilitiesControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void reportsThatPaymentProcessingIsNotYetAvailable() throws Exception {
        mvc.perform(get("/api/v1/payments/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentProcessingEnabled").value(false))
            .andExpect(jsonPath("$.accountsEnabled").value(true))
            .andExpect(jsonPath("$.ledgerEnabled").value(true))
            .andExpect(jsonPath("$.chapter").value(3));
    }
}
