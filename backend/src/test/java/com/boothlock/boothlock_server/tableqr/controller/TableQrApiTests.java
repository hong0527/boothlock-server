package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O4·O4b QR 다운로드 (명세서 O4·O4b) */
@SpringBootTest
@AutoConfigureMockMvc
class TableQrApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired TableRepository tableRepository;
    @Autowired BoothJwtProvider jwtProvider;

    private BoothEntity booth;
    private TableEntity table;
    private String token;

    @BeforeEach
    void setUp() {
        tableRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        booth = boothRepository.save(new BoothEntity("QR 다운로드 부스", "은행 1234", null));
        table = tableRepository.save(new TableEntity(booth, "A-1", "table-token-1"));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "qr-admin", hash, LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        token = jwtProvider.issue(staff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        tableRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void downloadsPngByDefault() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/admin/tables/{id}/qr", table.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("table-A-1-qr.png")))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(body.length > 100);
        assertTrue(isPng(body));
    }

    @Test
    void downloadsPdfWhenRequested() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/admin/tables/{id}/qr", table.getId())
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(body.length > 100);
        assertTrue(isPdf(body));
    }

    @Test
    void rejectsUnsupportedFormat() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tables/{id}/qr", table.getId())
                        .param("format", "svg")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesTablesFromOtherBooths() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        TableEntity otherTable = tableRepository.save(new TableEntity(otherBooth, "B-1", "table-token-2"));

        mockMvc.perform(get("/api/v1/admin/tables/{id}/qr", otherTable.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tables/{id}/qr", table.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadsAllTablesAsOnePdf() throws Exception {
        tableRepository.save(new TableEntity(booth, "A-2", "table-token-3"));

        byte[] body = mockMvc.perform(get("/api/v1/admin/tables/qr.pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("booth-tables-qr.pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(isPdf(body));
    }

    @Test
    void rejectsBulkDownloadWithNoTables() throws Exception {
        tableRepository.deleteAll();

        mockMvc.perform(get("/api/v1/admin/tables/qr.pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private boolean isPng(byte[] bytes) {
        return bytes.length > 8 && (bytes[1] & 0xFF) == 'P' && (bytes[2] & 0xFF) == 'N' && (bytes[3] & 0xFF) == 'G';
    }

    private boolean isPdf(byte[] bytes) {
        return bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }
}
