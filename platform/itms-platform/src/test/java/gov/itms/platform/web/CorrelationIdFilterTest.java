package gov.itms.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    @Test
    @Tag("INT-007")
    void keepsSafeIncomingCorrelationId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-12345_xyz");
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-12345_xyz");
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)).isEqualTo("abc-12345_xyz");
    }

    @Test
    @Tag("INT-007")
    void replacesUnsafeOrMissingCorrelationId() {
        assertThat(CorrelationIdFilter.resolve("bad\nvalue injected")).hasSize(36);
        assertThat(CorrelationIdFilter.resolve(null)).hasSize(36);
        assertThat(CorrelationIdFilter.resolve("short")).hasSize(36);
    }
}
