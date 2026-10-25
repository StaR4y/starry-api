package tech.starryapi.sourcetier;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

/** Administrator API of the tiered source pools. Every write is one transaction that bumps the configuration version. */
@RequestMapping("/admin/source-tiers")
public interface SourceTierAdminApi {

    /** The stored configuration of every vendor, and what this release can run (see {@link SourceTierDtos.Runtime}). */
    @GetMapping
    Mono<ApiResponse<SourceTierDtos.Overview>> overview(ServerWebExchange exchange);

    /**
     * What the answering node would do with requests now and how the last 15 minutes were served ({@link
     * SourceTierDtos.Status}). 503 {@code TIER_CONFIG_UNAVAILABLE} before the configuration has loaded.
     */
    @GetMapping("/status")
    Mono<ApiResponse<SourceTierDtos.Status>> status(ServerWebExchange exchange);

    /** The tier each account is in, for the account list ({@link SourceTierDtos.Placements}). */
    @GetMapping("/placements")
    Mono<ApiResponse<SourceTierDtos.Placements>> placements(ServerWebExchange exchange);

    /**
     * The pools a user can choose from for an API key of {@code provider} ({@link SourceTierDtos.MyTiers}): name, level,
     * price factor and price, nothing of the accounts behind them. Any signed-in user may ask; a user is only shown the
     * pools of a vendor that lists them publicly, an administrator always.
     */
    @GetMapping("/me")
    Mono<ApiResponse<SourceTierDtos.MyTiers>> mine(ServerWebExchange exchange, @RequestParam String provider);

    /**
     * Replaces the configuration of one vendor. 400 with a {@code SOURCE_*} code for a configuration that is not valid, 409
     * {@code SOURCE_TIER_VERSION_CONFLICT} when it changed since {@code expectedVersion}, 409
     * {@code SOURCE_TIER_RUNTIME_UNSUPPORTED} for a setting this release cannot run.
     */
    @PutMapping("/{vendor}")
    Mono<ApiResponse<SourceTierDtos.Vendor>> replace(
        ServerWebExchange exchange,
        @PathVariable String vendor,
        @Valid @RequestBody SourceTierDtos.ReplaceVendor request
    );

    /** Adds, updates and removes member rows of one vendor; the errors are those of {@link #replace}. */
    @PostMapping("/{vendor}/members")
    Mono<ApiResponse<SourceTierDtos.Vendor>> upsertMembers(
        ServerWebExchange exchange,
        @PathVariable String vendor,
        @Valid @RequestBody SourceTierDtos.UpsertMembers request
    );
}
