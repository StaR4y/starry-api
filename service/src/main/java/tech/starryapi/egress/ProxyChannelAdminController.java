package tech.starryapi.egress;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class ProxyChannelAdminController implements ProxyChannelAdminApi {
    private final ProxyChannelService service; private final AdminAuth auth;
    public ProxyChannelAdminController(ProxyChannelService service, AdminAuth auth) { this.service=service; this.auth=auth; }
    public Mono<ApiResponse<ProxyChannelDtos.Catalog>> catalog(ServerWebExchange e){auth.verifyAdmin(e);return service.catalog().map(ApiResponse::ok);}
    public Mono<ApiResponse<ProxyChannelDtos.Channel>> create(ServerWebExchange e, ProxyChannelDtos.CreateChannel r){auth.verifyAdmin(e);return service.create(r).map(ApiResponse::ok);}
    public Mono<ApiResponse<ProxyChannelDtos.Channel>> update(ServerWebExchange e, UUID id, ProxyChannelDtos.UpdateChannel r){auth.verifyAdmin(e);return service.update(id,r).map(ApiResponse::ok);}
    public Mono<ApiResponse<Void>> delete(ServerWebExchange e, UUID id){auth.verifyAdmin(e);return service.delete(id).thenReturn(ApiResponse.ok(null));}
    public Mono<ApiResponse<ProxyChannelDtos.ProbeResult>> test(ServerWebExchange e, UUID id){auth.verifyAdmin(e);return service.test(id).map(ApiResponse::ok);}
    public Mono<ApiResponse<ProxyChannelDtos.SyncResult>> subscribe(ServerWebExchange e, ProxyChannelDtos.ImportSubscription r){auth.verifyAdmin(e);return service.subscribe(r).map(ApiResponse::ok);}
    public Mono<ApiResponse<ProxyChannelDtos.SyncResult>> sync(ServerWebExchange e, UUID id){auth.verifyAdmin(e);return service.sync(id).map(ApiResponse::ok);}
}
