package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void propagatesValidRequestIdToMdcAndResponseAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(RequestIdFilter.HEADER, "req-12345_abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] observedInChain = new String[1];

        filter.doFilter(
                request,
                response,
                (req, res) -> observedInChain[0] = MDC.get(RequestIdFilter.MDC_KEY));

        assertThat(observedInChain[0]).isEqualTo("req-12345_abc");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-12345_abc");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenMissingOrUnsafe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader(RequestIdFilter.HEADER, "bad\r\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertThat(echoed).isNotBlank().isNotEqualTo("bad\r\nid");
        assertThat(echoed).hasSize(36);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
