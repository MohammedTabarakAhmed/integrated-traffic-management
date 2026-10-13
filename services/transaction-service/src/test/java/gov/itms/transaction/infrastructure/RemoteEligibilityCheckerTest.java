package gov.itms.transaction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gov.itms.platform.client.ServiceClients;
import gov.itms.platform.client.ServiceTokenProvider;
import gov.itms.transaction.application.EligibilityChecker.Block;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.Subject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@Tag("CC-032")
class RemoteEligibilityCheckerTest {

    private static final String REF = "VL_RENEW-R1-2026-0000001";
    private static final List<Subject> PARTIES = List.of(new Subject(Subject.Type.VEHICLE, "VIN-1"),
            new Subject(Subject.Type.TRAFFIC_FILE, "TF-1"));

    private MockRestServiceServer idp;
    private MockRestServiceServer hold;
    private RemoteEligibilityChecker checker;

    @BeforeEach
    void setUp() {
        RestClient.Builder idpBuilder = RestClient.builder().baseUrl("http://idp/token");
        idp = MockRestServiceServer.bindTo(idpBuilder).build();
        idp.expect(ExpectedCount.manyTimes(), requestTo("http://idp/token")).andRespond(withSuccess(
                "{\"access_token\":\"svc-tok\",\"token_type\":\"Bearer\",\"expires_in\":300}",
                MediaType.APPLICATION_JSON));
        RestClient.Builder holdBuilder = RestClient.builder().baseUrl("http://hold");
        hold = MockRestServiceServer.bindTo(holdBuilder).build();
        checker = new RemoteEligibilityChecker(ServiceClients.create(holdBuilder, new ServiceTokenProvider(
                idpBuilder.build(), "itms-transaction-service", "secret",
                Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC))));
    }

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (DomainException e) {
            return e.kind() + " " + e.code();
        }
        return "none";
    }

    @Test
    @DisplayName("CC-032: asks with the service's own token, the channel and the parties; returns the blocks")
    void asksAndReturnsBlocks() {
        hold.expect(requestTo("http://hold/v1/eligibility-checks")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-tok"))
                .andExpect(header("X-Channel", "PORTAL"))
                .andExpect(content().json("""
                        {"serviceCode":"VL_RENEW","transactionReference":"VL_RENEW-R1-2026-0000001",
                         "subjects":[{"type":"VEHICLE","id":"VIN-1"},{"type":"TRAFFIC_FILE","id":"TF-1"}]}""", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"serviceCode":"VL_RENEW","checkedOn":"2026-10-05","eligible":false,"blocks":[
                          {"reasonCode":"UNPAID_FINES","nextStep":"PAY_FINES","message":"Pay the fines."}]}""",
                        MediaType.APPLICATION_JSON));
        assertThat(checker.check("VL_RENEW", PARTIES, REF, Channel.PORTAL))
                .containsExactly(new Block("UNPAID_FINES", "PAY_FINES", "Pay the fines."));

        hold.reset();
        hold.expect(requestTo("http://hold/v1/eligibility-checks")).andRespond(withSuccess("""
                {"serviceCode":"VL_RENEW","checkedOn":"2026-10-05","eligible":true,"blocks":[]}""",
                MediaType.APPLICATION_JSON));
        assertThat(checker.check("VL_RENEW", PARTIES, REF, Channel.BACKOFFICE)).isEmpty();
        hold.verify();
    }

    @Test
    @DisplayName("CC-032: refusals, errors and incomplete answers are 'unavailable' (fail closed), never eligible")
    void failsClosed() {
        String[] incomplete = {
                "{\"serviceCode\":\"VL_OTHER\",\"eligible\":true,\"blocks\":[]}",
                "{\"serviceCode\":\"VL_RENEW\",\"blocks\":[]}",
                "{\"serviceCode\":\"VL_RENEW\",\"eligible\":true}",
                "{\"serviceCode\":\"VL_RENEW\",\"eligible\":false,\"blocks\":[]}",
                "{\"serviceCode\":\"VL_RENEW\",\"eligible\":true,\"blocks\":[{\"reasonCode\":\"X\",\"nextStep\":\"Y\","
                        + "\"message\":\"m\"}]}",
                "{\"serviceCode\":\"VL_RENEW\",\"eligible\":false,\"blocks\":[{\"reasonCode\":\"X\",\"nextStep\":\"Y\","
                        + "\"message\":\" \"}]}",
                "{\"serviceCode\":\"VL_RENEW\",\"eligible\":false,\"blocks\":[{\"reasonCode\":\"X\",\"message\":\"m\"}]}",
                "null"};
        for (String body : incomplete) {
            hold.reset();
            hold.expect(requestTo("http://hold/v1/eligibility-checks"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThat(code(() -> checker.check("VL_RENEW", PARTIES, REF, Channel.PORTAL))).as(body)
                    .isEqualTo("UNAVAILABLE ELIGIBILITY_UNAVAILABLE");
        }
        for (HttpStatus status : new HttpStatus[] {HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED,
                HttpStatus.BAD_REQUEST, HttpStatus.SERVICE_UNAVAILABLE}) {
            hold.reset();
            hold.expect(requestTo("http://hold/v1/eligibility-checks")).andRespond(withStatus(status));
            assertThat(code(() -> checker.check("VL_RENEW", PARTIES, REF, Channel.PORTAL))).as(status.toString())
                    .isEqualTo("UNAVAILABLE ELIGIBILITY_UNAVAILABLE");
        }
        hold.reset();
        hold.expect(requestTo("http://hold/v1/eligibility-checks")).andRespond(withServerError());
        assertThat(code(() -> checker.check("VL_RENEW", PARTIES, REF, Channel.PORTAL)))
                .isEqualTo("UNAVAILABLE ELIGIBILITY_UNAVAILABLE");
    }

    @Test
    @DisplayName("CC-032: no service token means no check, and no submission")
    void noTokenNoCheck() {
        RestClient.Builder idpBuilder = RestClient.builder().baseUrl("http://idp/token");
        MockRestServiceServer failingIdp = MockRestServiceServer.bindTo(idpBuilder).build();
        failingIdp.expect(requestTo("http://idp/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        RestClient.Builder holdBuilder = RestClient.builder().baseUrl("http://hold");
        MockRestServiceServer untouched = MockRestServiceServer.bindTo(holdBuilder).build();
        RemoteEligibilityChecker noToken = new RemoteEligibilityChecker(ServiceClients.create(holdBuilder,
                new ServiceTokenProvider(idpBuilder.build(), "c", "s", Clock.systemUTC())));
        assertThat(code(() -> noToken.check("VL_RENEW", PARTIES, REF, Channel.PORTAL)))
                .isEqualTo("UNAVAILABLE ELIGIBILITY_UNAVAILABLE");
        untouched.verify();
    }
}
