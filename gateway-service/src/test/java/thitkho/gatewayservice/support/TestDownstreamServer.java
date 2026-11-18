package thitkho.gatewayservice.support;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class TestDownstreamServer implements AutoCloseable {
    private final ConcurrentLinkedQueue<ForwardedRequest> requests = new ConcurrentLinkedQueue<>();
    private final DisposableServer disposableServer;

    public TestDownstreamServer(String responseBody) {
        disposableServer = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> request.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap(body -> {
                            Map<String, List<String>> headers = new LinkedHashMap<>();
                            request.requestHeaders().names().forEach(name ->
                                    headers.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(request.requestHeaders().getAll(name))));
                            requests.add(new ForwardedRequest(request.method().name(), request.uri(), Map.copyOf(headers), body));
                            return response.header("Content-Type", "text/plain").sendString(Mono.just(responseBody)).then();
                        }))
                .bindNow();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + disposableServer.port());
    }

    public ForwardedRequest takeRequest() {
        return requests.remove();
    }

    public boolean hasRequests() {
        return !requests.isEmpty();
    }

    public void clear() {
        requests.clear();
    }

    @Override
    public void close() {
        disposableServer.disposeNow();
    }

    public record ForwardedRequest(String method, String uri, Map<String, List<String>> headers, String body) {
    }
}
