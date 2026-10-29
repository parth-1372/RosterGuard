package com.rosterguard.roster;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.security.Role;
import com.rosterguard.security.TestTokenFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * HTTP-layer integration tests for roster import endpoints. Exercises the real
 * filter chain via MockMvc so role enforcement, tenant isolation and error shapes
 * are verified on actual HTTP requests — not just service-level unit tests.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Auth: no token, wrong role → 401/403</li>
 *   <li>HR_INTEGRATION: upload succeeds (202), receives correct JSON shape</li>
 *   <li>HR_INTEGRATION: cannot GET, approve or reject → 403</li>
 *   <li>OPERATOR: can GET status, approve, reject</li>
 *   <li>OPERATOR: cannot upload → 403</li>
 *   <li>Malformed request: empty file, bad snapshotGeneratedAt, bad UUID path → 400/422</li>
 *   <li>Tenant isolation: T2 operator cannot see/act on T1 import → 404</li>
 *   <li>Approve/reject non-AWAITING_APPROVAL import → 404</li>
 *   <li>PII-safe errors: 401/403 bodies contain no employee data</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RosterImportIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String IMPORT_URL = "/api/v1/roster-imports";
    private static final String HEADER =
            "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode";
    private static final String SNAPSHOT = "2024-01-15T12:00:00Z";
    private static final Instant BEFORE = Instant.parse("2024-01-15T10:00:00Z");

    private static byte[] csvBytes(String body) {
        return (HEADER + "\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] singleValidRow() {
        return csvBytes(
                "E001,1,2024-01-15T10:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING");
    }

    private ResultActions upload(byte[] csv, String snapshotAt, String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "roster.csv", "text/csv", csv);
        MockMultipartHttpServletRequestBuilder req =
                MockMvcRequestBuilders.multipart(IMPORT_URL).file(file);
        if (snapshotAt != null) req.param("snapshotGeneratedAt", snapshotAt);
        if (token != null)     req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    private ResultActions getImport(String importId, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(IMPORT_URL + "/" + importId)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions approve(String importId, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(IMPORT_URL + "/" + importId + "/approve")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions reject(String importId, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(IMPORT_URL + "/" + importId + "/reject")
                .header("Authorization", "Bearer " + token));
    }

    private String hrToken(String tenant) {
        return TestTokenFactory.validToken("hr-sys", tenant, Role.HR_INTEGRATION.name());
    }

    private String opToken(String tenant) {
        return TestTokenFactory.validToken("op", tenant, Role.OPERATOR.name());
    }

    private String taToken(String tenant) {
        return TestTokenFactory.validToken("ta", tenant, Role.TRANSPORT_ADMIN.name());
    }

    /** Seed an employee with a known source_updated_at so deactivation works. */
    private void seedActiveEmployee(String tenantId, String employeeId) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, source_version,
                     source_updated_at, roster_hash, payload_hash, status)
                VALUES (?, ?, ?, ?, 1,
                        '2024-01-15T10:00:00Z',
                        repeat('a', 64), repeat('b', 64), 'ACTIVE')
                """, tenantId, employeeId, "Seed " + employeeId,
                "seed." + employeeId.toLowerCase() + "@example.com");
    }

    // ── 1. No token → 401 ────────────────────────────────────────────────────

    @Test
    void noToken_upload_returns401() throws Exception {
        upload(singleValidRow(), SNAPSHOT, null)
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").exists());
    }

    @Test
    void noToken_getImport_returns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(IMPORT_URL + "/00000000-0000-0000-0000-000000000001"))
           .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    // ── 2. Wrong roles → 403 ─────────────────────────────────────────────────

    @Test
    void operator_cannotUpload_gets403() throws Exception {
        upload(singleValidRow(), SNAPSHOT, opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transportAdmin_cannotUpload_gets403() throws Exception {
        upload(singleValidRow(), SNAPSHOT, taToken(T1))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void hrIntegration_cannotGetImport_gets403() throws Exception {
        // First create an import so we have a real UUID.
        String id = uploadAndGetId(T1);
        getImport(id, hrToken(T1))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void hrIntegration_cannotApprove_gets403() throws Exception {
        String id = uploadAndGetId(T1);
        approve(id, hrToken(T1))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void hrIntegration_cannotReject_gets403() throws Exception {
        String id = uploadAndGetId(T1);
        reject(id, hrToken(T1))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    // ── 3. HR_INTEGRATION uploads successfully ────────────────────────────────

    @Test
    void hrIntegration_upload_returns202WithImportView() throws Exception {
        ResultActions ra = upload(singleValidRow(), SNAPSHOT, hrToken(T1));
        ra.andExpect(MockMvcResultMatchers.status().isAccepted())
          .andExpect(MockMvcResultMatchers.jsonPath("$.id").exists())
          .andExpect(MockMvcResultMatchers.jsonPath("$.status").exists())
          .andExpect(MockMvcResultMatchers.jsonPath("$.totalRows").value(1));
    }

    @Test
    void upload_completedImport_statusIsCompleted() throws Exception {
        ResultActions ra = upload(singleValidRow(), SNAPSHOT, hrToken(T1));
        ra.andExpect(MockMvcResultMatchers.status().isAccepted())
          .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"))
          .andExpect(MockMvcResultMatchers.jsonPath("$.newCount").value(1))
          .andExpect(MockMvcResultMatchers.jsonPath("$.rejectedRows").value(0));
    }

    @Test
    void upload_noWatermark_statusIsAwaitingApproval() throws Exception {
        // Pre-seed an employee that will be missing from the file.
        for (int i = 1; i <= 5; i++) seedActiveEmployee(T1, "SEED" + i);

        ResultActions ra = upload(singleValidRow(), null /* no watermark */, hrToken(T1));
        ra.andExpect(MockMvcResultMatchers.status().isAccepted())
          .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("AWAITING_APPROVAL"))
          .andExpect(MockMvcResultMatchers.jsonPath("$.holdReason")
                  .value("NO_SNAPSHOT_WATERMARK"));
    }

    @Test
    void upload_duplicateId_statusIsNeedsReview() throws Exception {
        // Two rows with the same employeeId.
        byte[] csv = csvBytes(
                "E001,1,2024-01-15T10:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING\n"
                + "E001,2,2024-01-15T11:00:00Z,Alice2,alice2@example.com,+919000000002,,BLR,MORNING");
        ResultActions ra = upload(csv, SNAPSHOT, hrToken(T1));
        ra.andExpect(MockMvcResultMatchers.status().isAccepted())
          .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("NEEDS_REVIEW"))
          .andExpect(MockMvcResultMatchers.jsonPath("$.holdReason")
                  .value("DUPLICATE_EMPLOYEE_ID"));
    }

    // ── 4. OPERATOR can get, approve, reject ──────────────────────────────────

    @Test
    void operator_canGetImportStatus() throws Exception {
        String id = uploadAndGetId(T1);
        getImport(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(id))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").exists());
    }

    @Test
    void operator_canApproveHeldImport() throws Exception {
        // Create a threshold-triggered AWAITING_APPROVAL import.
        for (int i = 1; i <= 10; i++) seedActiveEmployee(T1, "APPR" + i);
        byte[] csv = buildCsvRows(1, 7); // 7/10 = 3 missing = 30% = threshold
        String id = uploadAndGetId(T1, csv, SNAPSHOT);

        // Verify it's held.
        getImport(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status")
                        .value("AWAITING_APPROVAL"));

        // Approve.
        approve(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void operator_canRejectHeldImport() throws Exception {
        for (int i = 1; i <= 10; i++) seedActiveEmployee(T1, "APPR" + i);
        byte[] csv = buildCsvRows(1, 7);
        String id = uploadAndGetId(T1, csv, SNAPSHOT);

        reject(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("REJECTED"));
    }

    // ── 5. Malformed requests ─────────────────────────────────────────────────

    @Test
    void emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "roster.csv", "text/csv",
                new byte[0]);
        mvc.perform(MockMvcRequestBuilders.multipart(IMPORT_URL).file(empty)
                .header("Authorization", "Bearer " + hrToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isBadRequest())
           .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void badSnapshotFormat_returns400() throws Exception {
        upload(singleValidRow(), "not-a-date", hrToken(T1))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void badImportUuid_returns400() throws Exception {
        getImport("not-a-uuid", opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").exists());
    }

    @Test
    void unknownImportId_returns404() throws Exception {
        getImport("00000000-0000-0000-0000-000000000099", opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void approveCompleted_import_returns404() throws Exception {
        // A COMPLETED import cannot be approved again.
        String id = uploadAndGetId(T1);
        // Verify it's completed.
        getImport(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"));
        // Try to approve — should be 404 (not in AWAITING_APPROVAL).
        approve(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    // ── 6. Tenant isolation ───────────────────────────────────────────────────

    @Test
    void t2Operator_cannotSeeT1Import_gets404() throws Exception {
        String id = uploadAndGetId(T1);
        // T2 operator asks for T1's import UUID.
        getImport(id, opToken(T2))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void t2Operator_cannotApproveT1Import_gets404() throws Exception {
        // Create a threshold-held import in T1.
        for (int i = 1; i <= 10; i++) seedActiveEmployee(T1, "APPR" + i);
        byte[] csv = buildCsvRows(1, 7);
        String id = uploadAndGetId(T1, csv, SNAPSHOT);

        // T2 tries to approve.
        approve(id, opToken(T2))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
        // T1 import is still AWAITING_APPROVAL.
        getImport(id, opToken(T1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status")
                        .value("AWAITING_APPROVAL"));
    }

    // ── 7. PII-safe error bodies ──────────────────────────────────────────────

    @Test
    void unauthorised_uploadBody_containsNoPii() throws Exception {
        // CSV contains employee name and email — must not appear in 401 response.
        ResultActions ra = upload(singleValidRow(), SNAPSHOT, null);
        ra.andExpect(MockMvcResultMatchers.status().isUnauthorized());
        String body = ra.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("alice@example.com");
        assertThat(body).doesNotContain("Alice");
        assertThat(body).doesNotContain("9000000001");
    }

    @Test
    void forbidden_uploadBody_containsNoPii() throws Exception {
        ResultActions ra = upload(singleValidRow(), SNAPSHOT, opToken(T1));
        ra.andExpect(MockMvcResultMatchers.status().isForbidden());
        String body = ra.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("alice@example.com");
        assertThat(body).doesNotContain("Alice");
    }

    // ── 8. Import view contains no internal hashes ────────────────────────────

    @Test
    void importView_doesNotExposeInternalHashes() throws Exception {
        ResultActions ra = upload(singleValidRow(), SNAPSHOT, hrToken(T1));
        String body = ra.andReturn().getResponse().getContentAsString();
        // roster_hash and payload_hash should not be in the public view.
        assertThat(body).doesNotContain("rosterHash");
        assertThat(body).doesNotContain("payloadHash");
        assertThat(body).doesNotContain("roster_hash");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String uploadAndGetId(String tenant) throws Exception {
        return uploadAndGetId(tenant, singleValidRow(), SNAPSHOT);
    }

    private String uploadAndGetId(String tenant, byte[] csv, String snapshot) throws Exception {
        ResultActions ra = upload(csv, snapshot, hrToken(tenant));
        ra.andExpect(MockMvcResultMatchers.status().isAccepted());
        return ra.andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    /**
     * Build a CSV with rows for employees seeded as "APPR{n}" / "REJ{n}" / "TI{n}".
     * Rows from {@code from} to {@code to} inclusive (1-indexed).
     */
    private static byte[] buildCsvRows(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            // We can't know the prefix here; this is called for APPR, REJ and TI prefixes.
            // Use a generic pattern matching what seedActiveEmployee creates.
            sb.append("APPR").append(i).append(",2,2024-01-15T10:00:00Z,")
              .append("Updated APPR").append(i).append(",appr").append(i)
              .append("@example.com,+919000000001,,BLR,MORNING\n");
        }
        return csvBytes(sb.toString().strip());
    }
}
