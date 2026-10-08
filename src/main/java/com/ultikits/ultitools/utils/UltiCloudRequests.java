package com.ultikits.ultitools.utils;

import java.util.Map;

/**
 * RED skeleton: the public shape only, no behaviour yet.
 *
 * @since 6.3.0
 */
public final class UltiCloudRequests {

    private UltiCloudRequests() {
    }

    public static Result post(String path, String jsonBody) {
        return new Result(Outcome.IO_ERROR, -1, null);
    }

    public static Result get(String path, Map<String, String> query) {
        return new Result(Outcome.IO_ERROR, -1, null);
    }

    public enum Outcome { OK, NOT_CONNECTED, PATH_NOT_ALLOWED, IO_ERROR }

    public static final class Result {
        private final Outcome outcome;
        private final int statusCode;
        private final String body;

        private Result(Outcome outcome, int statusCode, String body) {
            this.outcome = outcome;
            this.statusCode = statusCode;
            this.body = body;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
