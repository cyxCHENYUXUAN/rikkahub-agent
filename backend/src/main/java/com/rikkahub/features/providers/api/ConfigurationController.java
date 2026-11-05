package com.rikkahub.features.providers.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import com.rikkahub.features.providers.application.ProviderApplicationService;
import com.rikkahub.features.assistants.application.AssistantApplicationService;
import com.rikkahub.platform.concurrency.SessionCommandQueue;
import com.rikkahub.platform.http.ApiResponses;
import com.rikkahub.platform.idempotency.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ConfigurationController {
    private final ProviderApplicationService providers;
    private final AssistantApplicationService assistants;
    private final IdempotencyService idempotency;
    private final SessionCommandQueue commands;
    public ConfigurationController(ProviderApplicationService providers,AssistantApplicationService assistants,IdempotencyService idempotency,SessionCommandQueue commands){this.providers=providers;this.assistants=assistants;this.idempotency=idempotency;this.commands=commands;}
    @GetMapping("/providers") public List<ProviderApplicationService.ProviderView> providers(){return providers.list();}
    @PostMapping("/providers") public ResponseEntity<JsonNode> provider(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){return ApiResponses.response(ApiResponses.await(commands.submit("configuration",()->idempotency.execute("POST:/providers",key,body,()->new IdempotencyService.Reply(201,providers.createInTransaction(body))))));}
    @GetMapping("/assistants") public List<AssistantApplicationService.AssistantProfile> assistants(){return assistants.list();}
    @PostMapping("/assistants") public ResponseEntity<JsonNode> assistant(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){return ApiResponses.response(ApiResponses.await(commands.submit("configuration",()->idempotency.execute("POST:/assistants",key,body,()->new IdempotencyService.Reply(201,assistants.createInTransaction(body))))));}
    @GetMapping("/models") public List<java.util.Map<String,Object>> models(){return assistants.list().stream().map(a->java.util.Map.<String,Object>of("providerId",a.providerId(),"model",a.model(),"contextBudgetTokens",a.contextWindowTokens())).distinct().toList();}
}
