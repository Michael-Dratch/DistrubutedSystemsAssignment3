package messages;

public interface OrchMessage {
    public record Start(int serverCount, int clientCount, int concurrentFailures, int numClientRequests) implements OrchMessage {}
    public record ShutDown() implements OrchMessage {}
    public record ServerTerminated() implements OrchMessage {}
    public record ClientTerminated() implements OrchMessage {}
    public record ShutDownComplete() implements OrchMessage {}
}
