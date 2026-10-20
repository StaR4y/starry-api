package tech.starryapi.news;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.news.NewsDtos.DailyIndex;
import tech.starryapi.news.NewsDtos.DailyReport;
import tech.starryapi.news.NewsDtos.HotTopics;

@RestController
@RequestMapping("/news")
public class NewsController {

    private final NewsService news;
    private final AdminAuth auth;

    public NewsController(NewsService news, AdminAuth auth) {
        this.news = news;
        this.auth = auth;
    }

    @GetMapping("/dailies")
    public Mono<ApiResponse<DailyIndex>> dailies(
        ServerWebExchange exchange,
        @RequestParam(defaultValue = "60") int limit
    ) {
        auth.verify(exchange);
        return news.dailies(limit).map(ApiResponse::ok);
    }

    @GetMapping("/dailies/{date}")
    public Mono<ApiResponse<DailyReport>> daily(ServerWebExchange exchange, @PathVariable String date) {
        auth.verify(exchange);
        return news.daily(date).map(ApiResponse::ok);
    }

    @GetMapping("/hot-topics")
    public Mono<ApiResponse<HotTopics>> hotTopics(ServerWebExchange exchange) {
        auth.verify(exchange);
        return news.hotTopics().map(ApiResponse::ok);
    }
}
