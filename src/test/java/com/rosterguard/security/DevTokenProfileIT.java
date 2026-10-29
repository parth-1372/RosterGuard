package com.rosterguard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rosterguard.PostgresIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves that DevTokenController and its unauthenticated minting route (/api/dev/token)
 * are strictly unavailable outside the "dev" profile.
 */
class DevTokenProfileIT {

    @SpringBootTest
    @AutoConfigureMockMvc
    @Nested
    class OutsideDevProfile extends PostgresIntegrationTest {

        @Autowired
        ApplicationContext context;

        @Autowired
        MockMvc mvc;

        @Test
        void devTokenControllerBeanIsNotRegistered() {
            assertThat(context.containsBean("devTokenController")).isFalse();
        }

        @Test
        void devTokenEndpointIsRefusedWith401Unauthorized() throws Exception {
            mvc.perform(post("/api/dev/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                            {"userId":"u1","tenant":"tenant-1","roles":["HR_INTEGRATION"]}
                            """))
                    .andExpect(status().isUnauthorized());
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("dev")
    @Nested
    class InsideDevProfile extends PostgresIntegrationTest {

        @Autowired
        ApplicationContext context;

        @Autowired
        MockMvc mvc;

        @Test
        void devTokenControllerBeanIsRegistered() {
            assertThat(context.containsBean("devTokenController")).isTrue();
        }

        @Test
        void devTokenEndpointMintsTokenSuccessfully() throws Exception {
            mvc.perform(post("/api/dev/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                            {"userId":"u1","tenant":"tenant-1","roles":["HR_INTEGRATION"]}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.expiresAt").exists());
        }
    }
}

