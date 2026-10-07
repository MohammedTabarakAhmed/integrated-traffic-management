package gov.itms.violation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.violation.application.Ports;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@Tag("VF-070")
class AdaptersTest {

    private final PartnerStub stub = new PartnerStub();
    private final RestClient client = RestClient.builder().baseUrl(stub.baseUrl()).build();
    private final RestClient nobody = RestClient.builder().baseUrl("http://127.0.0.1:1").build();

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    @Tag("FN-002")
    @DisplayName("VF-070, FN-002: a fine becomes a VIOLATION payable keyed by the violation; it can be withdrawn")
    void payables() {
        Adapters.RemotePayables payables = new Adapters.RemotePayables(client);
        UUID id = payables.register("V-2026-000000001", "TF-1", "NORTH", "XTS", 80_000, "FINE_REVENUE", "AUTHORITY",
                "x".repeat(250));
        assertThat(id).isNotNull();
        assertThat(stub.payableKeys).containsExactly("payable-v-2026-000000001");
        assertThat(stub.payables.getFirst().get("itemType").asString()).isEqualTo("VIOLATION");
        assertThat(stub.payables.getFirst().get("itemRef").asString()).isEqualTo("V-2026-000000001");
        assertThat(stub.payables.getFirst().get("lines").get(0).get("description").asString())
                .as("cut to the payable's limit").hasSize(200);
        payables.cancel(id, "violation cancelled: DUPLICATE");
        assertThat(stub.cancelledPayables).containsExactly(id.toString());
        assertThatThrownBy(() -> new Adapters.RemotePayables(nobody).register("V-1", "TF-1", "NORTH", "XTS", 1,
                "F", "A", "d")).isInstanceOf(Ports.UnavailableException.class).extracting("code")
                .isEqualTo("PAYMENTS_UNAVAILABLE");
    }

    @Test
    @Tag("VF-032")
    @Tag("CC-030")
    @DisplayName("VF-032, CC-030: a suspension places a LICENCE_SUSPENSION hold on the traffic file for its period")
    void holds() {
        String id = new Adapters.RemoteHolds(client, List.of("*"), List.of("HOLD_SUPERVISOR"))
                .placeSuspensionHold("TF-1", LocalDate.of(2026, 10, 6), LocalDate.of(2027, 1, 5), "SUSPENSION:abc",
                        "Licence suspended for 3 months");
        assertThat(id).isNotBlank();
        var body = stub.holds.getFirst();
        assertThat(body.get("subject").get("type").asString()).isEqualTo("TRAFFIC_FILE");
        assertThat(body.get("subject").get("id").asString()).isEqualTo("TF-1");
        assertThat(body.get("holdType").asString()).isEqualTo("LICENCE_SUSPENSION");
        assertThat(body.get("startDate").asString()).isEqualTo("2026-10-06");
        assertThat(body.get("endDate").asString()).isEqualTo("2027-01-05");
        assertThat(body.get("liftableByRoles").get(0).asString()).isEqualTo("HOLD_SUPERVISOR");
        assertThat(stub.holdKeys).containsExactly("hold-suspension-abc");
        stub.holdsDown = true;
        assertThatThrownBy(() -> new Adapters.RemoteHolds(client, List.of("*"), List.of("HOLD_SUPERVISOR"))
                .placeSuspensionHold("TF-1", LocalDate.of(2026, 10, 6), LocalDate.of(2027, 1, 5), "SUSPENSION:x",
                        "r")).isInstanceOf(Ports.UnavailableException.class).extracting("code")
                .isEqualTo("HOLDS_UNAVAILABLE");
    }
}
