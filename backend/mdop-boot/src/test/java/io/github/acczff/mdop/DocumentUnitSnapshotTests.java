package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUnitSnapshotTests extends InventoryScenarioSupport {
    @ParameterizedTest
    @CsvSource({"purchasing,DRAFT", "purchasing,REJECTED", "sales,DRAFT", "sales,REJECTED"})
    void unitChangeRequiresExplicitQuantityReviewBeforeSubmission(String module, String state)
            throws Exception {
        long target = warehouse;
        if (module.equals("sales"))
            target =
                    postAs(
                                    "/api/master-data/warehouses",
                                    Map.of(
                                            "code",
                                            "F" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                            "name",
                                            "成品仓",
                                            "purpose",
                                            "FINISHED_GOODS",
                                            "form",
                                            "PHYSICAL",
                                            "managementCategory",
                                            "GENERAL"),
                                    admin(),
                                    201)
                            .get("id")
                            .asLong();
        var fresh =
                postAs(
                        "/api/master-data/materials",
                        Map.of(
                                "code",
                                "U" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                "name",
                                "未授权物料",
                                "unit",
                                "件",
                                "trackingMode",
                                "QUANTITY",
                                "requireDateCode",
                                false,
                                "requireExpiry",
                                false),
                        admin(),
                        201);
        long materialId = fresh.get("id").asLong();
        String base = "/api/v1/" + module + "/documents";
        var writer = actor(module, target, "writer", "write");
        var reviewer = actor(module, target, "reviewer", "review");
        var body =
                new HashMap<String, Object>(
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "warehouseId",
                                target,
                                "purpose",
                                "核对基本单位",
                                "neededDate",
                                "2026-12-01",
                                "lines",
                                List.of(Map.of("materialId", materialId, "quantity", "10"))));
        if (module.equals("sales"))
            body.put(
                    "customerId",
                    postAs(
                                    "/api/master-data/customers",
                                    Map.of(
                                            "code",
                                            "C" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                            "name",
                                            "客户"),
                                    admin(),
                                    201)
                            .get("id")
                            .asLong());
        var document = postAs(base, body, writer, 201);
        String url = base + "/" + document.get("id").asLong();
        if (state.equals("REJECTED")) {
            document = postAs(url + "/actions/submit", command(document), writer, 200);
            document = postAs(url + "/actions/reject", command(document), reviewer, 200);
        }
        String changedUnit = "箱" + key().substring(0, 6);
        long unitId =
                postAs(
                                "/api/master-data/units",
                                Map.of(
                                        "code",
                                        "U" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        changedUnit),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        // Exercise the supported catalog API; these drafts have not locked material identity.
        putDocument(
                "/api/master-data/materials/" + materialId,
                Map.of(
                        "version",
                        fresh.get("version").asLong(),
                        "name",
                        "未授权物料",
                        "status",
                        "ENABLED",
                        "unitId",
                        unitId,
                        "trackingMode",
                        "QUANTITY",
                        "requireDateCode",
                        false,
                        "requireExpiry",
                        false,
                        "reason",
                        "业务维护基本单位"),
                admin());
        postAs(url + "/actions/submit", command(document), writer, 409);
        var unchanged =
                json.readTree(
                        mvc.perform(get(url).with(writer))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(unchanged.get("status").asText()).isEqualTo(state);
        assertThat(unchanged.get("version")).isEqualTo(document.get("version"));
        assertThat(unchanged.get("lines")).isEqualTo(document.get("lines"));
        assertThat(unchanged.get("history").size()).isEqualTo(document.get("history").size());

        body.put("idempotencyKey", key());
        body.put("version", unchanged.get("version").asLong());
        body.put("reason", "核对新单位后重新录入数量");
        body.put("lines", List.of(Map.of("materialId", materialId, "quantity", "2")));
        document = putDocument(url, body, writer);
        document = postAs(url + "/actions/submit", command(document), writer, 200);
        document = postAs(url + "/actions/approve", command(document), reviewer, 200);
        assertThat(document.get("lines").get(0).get("unit").asText()).isEqualTo(changedUnit);
        assertThat(document.get("lines").get(0).get("quantity").asText()).isEqualTo("2.000000");
    }

    private Map<String, Object> command(JsonNode document) {
        return Map.of(
                "idempotencyKey",
                key(),
                "version",
                document.get("version").asLong(),
                "reason",
                "核对授权数量");
    }

    private RequestPostProcessor actor(String module, long target, String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority(module + ":read"),
                        new SimpleGrantedAuthority(module + ":" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + target));
    }

    private JsonNode putDocument(String url, Object body, RequestPostProcessor actor)
            throws Exception {
        return json.readTree(
                mvc.perform(
                                put(url).with(actor)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
