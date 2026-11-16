package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecTests extends MdopInfrastructureTestBase {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Test
    void openApiSpecMatchesCommittedContract() throws Exception {
        String raw =
                mockMvc.perform(get("/v3/api-docs").with(user("openapi").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        Object tree = objectMapper.readValue(raw, Object.class);
        String formatted =
                objectMapper
                                .writerWithDefaultPrettyPrinter()
                                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                                .writeValueAsString(tree)
                        + "\n";

        Path target = resolveSpecPath();
        if (Boolean.getBoolean("mdop.openapi.write")
                || "true".equalsIgnoreCase(System.getenv("MDOP_OPENAPI_WRITE"))) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, formatted, StandardCharsets.UTF_8);
            return;
        }

        if (Files.exists(target)) {
            String committed = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(formatted)
                    .as(
                            "OpenAPI spec drifted from %s. Re-run with -Dmdop.openapi.write=true to"
                                    + " regenerate.",
                            target)
                    .isEqualToNormalizingNewlines(committed);
        }
    }

    private static Path resolveSpecPath() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("frontend/apps/admin"))
                    && Files.isDirectory(dir.resolve("backend"))) {
                return dir.resolve("frontend/apps/admin/src/shared/api/openapi.json");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate repo root containing frontend/apps/admin");
    }
}
