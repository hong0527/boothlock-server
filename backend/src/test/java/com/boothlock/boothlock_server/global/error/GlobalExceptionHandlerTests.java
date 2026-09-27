package com.boothlock.boothlock_server.global.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 예외 처리 — DB 일시 불가는 503 한 줄 경고, 클라이언트 끊김은 본문 없음, 나머지는 500 + 스택트레이스 한 번(v0.6.13).
 * 예전엔 전부 500 안전망으로 떨어져 printStackTrace가 잠금 경합·끊김마다 표준에러를 채웠다.
 * 스프링 컨텍스트 없이 핸들러만 붙인다 — 어떤 예외가 어느 핸들러로 가는지(예외 계층 매칭)가 확인 대상이다
 */
class GlobalExceptionHandlerTests {

    @RestController
    static class ThrowingController {
        @GetMapping("/boom/{kind}")
        String boom(@PathVariable String kind) throws Exception {
            throw switch (kind) {
                case "lock" -> new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction");
                case "deadlock" -> new PessimisticLockingFailureException("Deadlock found when trying to get lock");
                case "pool" -> new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new SQLTransientConnectionException("boothlock - Connection is not available, request timed out after 10000ms."));
                case "gone" -> new DataAccessResourceFailureException("Communications link failure");
                case "slow" -> new QueryTimeoutException("Statement cancelled due to timeout");
                case "abort" -> new ClientAbortException(new IOException("Broken pipe"));
                case "async" -> new AsyncRequestNotUsableException("Response not usable after response errors.");
                case "pipe" -> new IOException("Connection reset by peer");
                case "io" -> new IOException("disk full");
                default -> new IllegalStateException("예상 못 한 버그");
            };
        }
    }

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        previousLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logs);
        handlerLogger.setLevel(previousLevel);
    }

    private List<ILoggingEvent> events() {
        return logs.list;
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "deadlock", "pool", "gone", "slow"})
    void temporaryDbFailuresAre503WithSingleLineWarning(String kind) throws Exception {
        mockMvc.perform(get("/boom/{kind}", kind))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("잠시 후 다시 시도해주세요."));

        assertEquals(1, events().size());
        ILoggingEvent event = events().get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy(), "스택트레이스 없이 한 줄만 남긴다");
    }

    @Test
    void poolTimeoutWarningCarriesRootCause() throws Exception {
        mockMvc.perform(get("/boom/pool")).andExpect(status().isServiceUnavailable());

        assertTrue(events().get(0).getFormattedMessage().contains("request timed out after 10000ms"),
                "원인(Hikari 대기 초과)이 한 줄 로그에 보여야 한다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abort", "async", "pipe"})
    void clientDisconnectsWriteNoBodyAndLogOnlyAtDebug(String kind) throws Exception {
        MvcResult result = mockMvc.perform(get("/boom/{kind}", kind)).andReturn();

        assertEquals("", result.getResponse().getContentAsString(), "끊긴 연결에 본문을 쓰지 않는다");
        assertEquals(1, events().size());
        assertEquals(Level.DEBUG, events().get(0).getLevel());
    }

    @Test
    void otherIoExceptionIsStill500() throws Exception {
        mockMvc.perform(get("/boom/io"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertEquals(Level.ERROR, events().get(0).getLevel());
    }

    @Test
    void unexpectedExceptionIs500LoggedOnceWithStackTrace() throws Exception {
        mockMvc.perform(get("/boom/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));

        assertEquals(1, events().size());
        ILoggingEvent event = events().get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrowableProxy(), "진짜 버그는 스택트레이스를 로거로 한 번 남긴다");
        assertEquals(IllegalStateException.class.getName(), event.getThrowableProxy().getClassName());
    }
}
