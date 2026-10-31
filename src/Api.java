/** An error that is reported to the browser as {"error": message} with the given HTTP status. */
final class Api extends RuntimeException {
    final int code;

    Api(int code, String message) {
        super(message);
        this.code = code;
    }
}
