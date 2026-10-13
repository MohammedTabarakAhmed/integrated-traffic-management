package gov.itms.ai.api;

import java.util.Map;

import gov.itms.ai.application.KillSwitchService;
import gov.itms.ai.application.Ports;
import gov.itms.ai.domain.Models;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Kill switch / emergency stop API (AI-014, AI-019).
 */
@RestController
@RequestMapping("/v1/kill-switch")
public class KillSwitchController {

    private final KillSwitchService killSwitchService;

    public KillSwitchController(KillSwitchService killSwitchService) {
        this.killSwitchService = killSwitchService;
    }

    @GetMapping("/state")
    @PreAuthorize("hasAuthority('ai.kill_switch.read')")
    public ResponseEntity<ApiModels.KillSwitchStateResponse> getState() {
        Ports.KillSwitch.State state = killSwitchService.getState();
        return ResponseEntity.ok(ApiModels.KillSwitchStateResponse.from(state));
    }

    @PostMapping("/activate")
    @PreAuthorize("hasAuthority('ai.kill_switch.activate')")
    public ResponseEntity<Void> activate(@RequestBody ApiModels.KillSwitchActivateRequest request) {
        killSwitchService.activate(request.reason(), request.activatedBy(), Models.KillSwitchScope.valueOf(request.scope()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/deactivate")
    @PreAuthorize("hasAuthority('ai.kill_switch.deactivate')")
    public ResponseEntity<Void> deactivate(@RequestBody Map<String, String> request) {
        killSwitchService.deactivate(request.get("deactivatedBy"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/check/{scope}")
    @PreAuthorize("hasAuthority('ai.kill_switch.read')")
    public ResponseEntity<Map<String, Boolean>> checkInferenceAllowed(@PathVariable String scope) {
        boolean allowed = killSwitchService.isInferenceAllowed(Models.KillSwitchScope.valueOf(scope));
        return ResponseEntity.ok(Map.of("allowed", allowed));
    }
}