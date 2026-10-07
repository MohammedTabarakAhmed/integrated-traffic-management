package gov.itms.vehicle.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.vehicle.application.Ports;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@Tag("VL-024")
class AdaptersTest {

    private static final String VIN = "WDB1234567A123456";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private final PartnerStub stub = new PartnerStub();
    private final RestClient client = RestClient.builder().baseUrl(stub.baseUrl()).build();

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    @DisplayName("ICD-02: the insurer's policies of the VIN are read; other VINs in the answer are ignored")
    void policies() {
        stub.policyEnds.put(VIN, TODAY.minusDays(1));
        List<Ports.InsuranceRegister.Policy> found = new Adapters.HttpInsuranceRegister(client).policies(VIN, TODAY);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().policyNo()).isEqualTo("POL-" + VIN);
        assertThat(found.getFirst().validOn(TODAY)).as("expired yesterday").isFalse();
        assertThat(found.getFirst().validOn(TODAY.minusDays(1))).isTrue();
    }

    @Test
    @DisplayName("ICD-02: no policy (404) is an empty answer; 503 or no server fails closed as unavailable")
    void failClosed() {
        assertThat(new Adapters.HttpInsuranceRegister(client).policies(VIN, TODAY)).isEmpty();
        stub.insurerDown.add(VIN);
        assertThatThrownBy(() -> new Adapters.HttpInsuranceRegister(client).policies(VIN, TODAY))
                .isInstanceOf(Ports.UnavailableException.class).extracting("code").isEqualTo("INSURANCE_UNAVAILABLE");
        RestClient nobody = RestClient.builder().baseUrl("http://127.0.0.1:1").build();
        assertThatThrownBy(() -> new Adapters.HttpInsuranceRegister(nobody).policies(VIN, TODAY))
                .isInstanceOf(Ports.UnavailableException.class);
    }

    @Test
    @Tag("CC-047")
    @Tag("FN-002")
    @DisplayName("CC-047, FN-002: quotes drop zero lines; payables carry an Idempotency-Key derived from the item")
    void feesAndPayables() {
        Ports.FeeQuotes.Quote q = new Adapters.RemoteFeeQuotes(client).quote("VEH_RENEWAL", "NORTH", "CITIZEN",
                "PRIVATE", TODAY);
        assertThat(q.total()).isEqualTo(40000);
        assertThat(q.lines()).extracting(Ports.FeeQuotes.Line::financialItem).containsExactly("REG_FEE");
        assertThat(stub.quotes.getFirst().get("expiryDate").asString()).isEqualTo(TODAY.toString());
        assertThat(stub.quotes.getFirst().get("subjectCategory").asString()).isEqualTo("PRIVATE");

        String itemRef = "VR-" + UUID.randomUUID().toString().toUpperCase();
        UUID id = new Adapters.RemotePayables(client).register(itemRef, "TF-1", "NORTH", "XTS", q.lines());
        assertThat(id).isNotNull();
        assertThat(stub.payableKeys).containsExactly("payable-" + itemRef.toLowerCase());
        assertThat(stub.payables.getFirst().get("itemType").asString()).isEqualTo("TRANSACTION");
        assertThat(stub.payables.getFirst().get("lines").get(0).get("amountMinor").asLong()).isEqualTo(40000);

        RestClient nobody = RestClient.builder().baseUrl("http://127.0.0.1:1").build();
        assertThatThrownBy(() -> new Adapters.RemoteFeeQuotes(nobody).quote("X", "NORTH", "CITIZEN", "PRIVATE", null))
                .isInstanceOf(Ports.UnavailableException.class).extracting("code").isEqualTo("FEES_UNAVAILABLE");
        assertThatThrownBy(() -> new Adapters.RemotePayables(nobody).register(itemRef, "TF-1", "NORTH", "XTS",
                q.lines())).isInstanceOf(Ports.UnavailableException.class).extracting("code")
                .isEqualTo("PAYMENTS_UNAVAILABLE");
    }
}
