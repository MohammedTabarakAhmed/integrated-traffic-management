package gov.itms.transaction.infrastructure;

import gov.itms.platform.client.OwnTrafficFile;
import gov.itms.platform.client.ServiceCallException;
import gov.itms.transaction.application.OwnFile;
import gov.itms.transaction.domain.DomainException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The customer's file through traffic-file-service with the customer's own token (ADR-067 d). */
class RemoteOwnFile implements OwnFile {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteOwnFile.class);

    private final OwnTrafficFile files;

    RemoteOwnFile(OwnTrafficFile files) {
        this.files = files;
    }

    @Override
    public Optional<String> fileNumber() {
        try {
            return files.fileNumber();
        } catch (ServiceCallException e) {
            LOG.warn("Own traffic file lookup failed: {}", e.getMessage());
            throw new DomainException(DomainException.Kind.UNAVAILABLE, "TRAFFIC_FILES_UNAVAILABLE",
                    "your traffic file cannot be found now", List.of("CC-025"));
        }
    }
}
