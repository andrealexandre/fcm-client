package com.google.fcm;

import com.google.gson.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.fcm.Constants.*;

/**
 * Simple {@link Sender} implementation. From gcm-server.
 *
 * @author André Alexandre
 * @since 1.0.0
 */
@Slf4j
public class SenderImpl implements Sender {

    private static final String UTF8 = "UTF-8";

    public static final int BACKOFF_INITIAL_DELAY_BEFORE_RETRY = 1000;
    public static final int MAX_BACKOFF_DELAY_BEFORE_RETRY = 1024000;

    private final Random random = new SecureRandom();
    private final String key;

    public SenderImpl(@NonNull String key) {
        this.key = key;
    }

    @Override
    public Result send(Message message) throws IOException {
        return send(message, "test", 3);
    }

    /**
     * Sends a message to one device, retrying in case of unavailability.
     *
     * <p>
     * <strong>Note: </strong> this method uses exponential back-off to retry in
     * case of service unavailability and hence could block the calling thread
     * for many seconds.
     *
     * @param message message to be sent, including the device's registration id.
     * @param to registration token, notification key, or topic where the message will be sent.
     * @param retries number of retries in case of service unavailability errors.
     *
     * @return result of the request (see its javadoc for more details).
     *
     * @throws IllegalArgumentException if to is {@literal null}.
     * @throws InvalidRequestException if GCM didn't returned a 200 or 5xx status.
     * @throws IOException if message could not be sent.
     */
    public Result send(Message message, String to, int retries) throws IOException {
        int attempt = 0;
        Result result;
        int backoff = BACKOFF_INITIAL_DELAY_BEFORE_RETRY;
        boolean tryAgain;
        do {
            attempt++;
            if (log.isTraceEnabled()) {
                log.trace("Attempt #" + attempt + " to send message " + message + " to regIds " + to);
            }
            result = sendNoRetry(message, to);
            tryAgain = result == null && attempt <= retries;
            if (tryAgain) {
                int sleepTime = backoff / 2 + random.nextInt(backoff);
                sleep(sleepTime);
                if (2 * backoff < MAX_BACKOFF_DELAY_BEFORE_RETRY) {
                    backoff *= 2;
                }
            }
        } while (tryAgain);
        if (result == null) {
            throw new IOException("Could not send message after " + attempt +
                    " attempts");
        }
        return result;
    }

    /**
     * Sends a message without retrying in case of service unavailability. See
     * {@link #send(Message, String, int)} for more info.
     *
     * @return result of the post, or {@literal null} if the GCM service was
     *         unavailable or any network exception caused the request to fail,
     *         or if the response contains more than one result.
     *
     * @throws InvalidRequestException if GCM didn't returned a 200 status.
     * @throws IllegalArgumentException if to is {@literal null}.
     */
    public Result sendNoRetry(Message message, String to) throws IOException {
        nonNull(to);
        Map<Object, Object> jsonRequest = new HashMap<Object, Object>();
        messageToMap(message, jsonRequest);
        jsonRequest.put(JSON_TO, to);
        String responseBody = makeGcmHttpRequest(jsonRequest);
        if (responseBody == null) {
            return null;
        }
        JsonParser parser = new JsonParser();
        JsonObject jsonResponse;
        try {
            jsonResponse = (JsonObject) parser.parse(responseBody);
            Result.ResultBuilder resultBuilder = Result.builder();
            if (jsonResponse.has("results")) {
                // Handle response from message sent to specific device.
                JsonArray jsonResults = (JsonArray) jsonResponse.get("results");
                if (jsonResults.size() == 1) {
                    JsonObject jsonResult = (JsonObject) jsonResults.get(0);
                    String messageId = (String) jsonResult.get(JSON_MESSAGE_ID).getAsString();
                    String canonicalRegId = (String) jsonResult.get(TOKEN_CANONICAL_REG_ID).getAsString();
                    String error = (String) jsonResult.get(JSON_ERROR).getAsString();
                    resultBuilder.messageId(messageId)
                            .canonicalRegistrationId(canonicalRegId)
                            .errorCode(error);
                } else {
                    log.warn("Found null or " + jsonResults.size() + " results, expected one");
                    return null;
                }
            } else if (to.startsWith(TOPIC_PREFIX)) {
                if (jsonResponse.has(JSON_MESSAGE_ID)) {
                    // message_id is expected when this is the response from a topic message.
                    Long messageId = (Long) jsonResponse.get(JSON_MESSAGE_ID).getAsLong();
                    resultBuilder.messageId(messageId.toString());
                } else if (jsonResponse.has(JSON_ERROR)) {
                    String error = (String) jsonResponse.get(JSON_ERROR).getAsString();
                    resultBuilder.errorCode(error);
                } else {
                    log.warn("Expected " + JSON_MESSAGE_ID + " or " + JSON_ERROR + " found: " + responseBody);
                    return null;
                }
            } else if (jsonResponse.has(JSON_SUCCESS) && jsonResponse.has(JSON_FAILURE)) {
                // success and failure are expected when response is from group message.
                int success = getNumber(jsonResponse, JSON_SUCCESS).intValue();
                int failure = getNumber(jsonResponse, JSON_FAILURE).intValue();
                List<String> failedIds = null;
                if (jsonResponse.has("failed_registration_ids")) {
                    JsonArray jFailedIds = (JsonArray) jsonResponse.get("failed_registration_ids");
                    failedIds = new ArrayList<String>();
                    for (int i = 0; i < jFailedIds.size(); i++) {
                        failedIds.add((String) jFailedIds.get(i).getAsString());
                    }
                }
                resultBuilder.success(success).failure(failure)
                        .failedRegistrationIds(failedIds);
            } else {
                log.warn("Unrecognized response: " + responseBody);
                throw newIoException(responseBody, new Exception("Unrecognized response."));
            }
            return resultBuilder.build();
        } catch (CustomParserException e) {
            throw newIoException(responseBody, e);
        }
    }

    /**
     * Sends a message to many devices, retrying in case of unavailability.
     *
     * <p>
     * <strong>Note: </strong> this method uses exponential back-off to retry in
     * case of service unavailability and hence could block the calling thread
     * for many seconds.
     *
     * @param message message to be sent.
     * @param regIds registration id of the devices that will receive
     *        the message.
     * @param retries number of retries in case of service unavailability errors.
     *
     * @return combined result of all requests made.
     *
     * @throws IllegalArgumentException if registrationIds is {@literal null} or
     *         empty.
     * @throws InvalidRequestException if GCM didn't returned a 200 or 503 status.
     * @throws IOException if message could not be sent.
     */
    public MulticastResult send(Message message, List<String> regIds, int retries)
            throws IOException {
        int attempt = 0;
        MulticastResult multicastResult;
        int backoff = BACKOFF_INITIAL_DELAY_BEFORE_RETRY;
        // Map of results by registration id, it will be updated after each attempt
        // to send the messages
        final Map<String, Result> results = new HashMap<String, Result>();
        List<String> unsentRegIds = new ArrayList<String>(regIds);
        boolean tryAgain;
        List<Long> multicastIds = new ArrayList<Long>();
        do {
            multicastResult = null;
            attempt++;
            if (log.isTraceEnabled()) {
                log.trace("Attempt #" + attempt + " to send message " + message + " to regIds " + unsentRegIds);
            }
            try {
                multicastResult = sendNoRetry(message, unsentRegIds);
            } catch(IOException e) {
                // no need for WARNING since exception might be already logged
                log.trace("IOException on attempt " + attempt, e);
            }
            if (multicastResult != null) {
                long multicastId = multicastResult.getMulticastId();
                log.trace("multicast_id on attempt # " + attempt + ": " + multicastId);
                multicastIds.add(multicastId);
                unsentRegIds = updateStatus(unsentRegIds, results, multicastResult);
                tryAgain = !unsentRegIds.isEmpty() && attempt <= retries;
            } else {
                tryAgain = attempt <= retries;
            }
            if (tryAgain) {
                int sleepTime = backoff / 2 + random.nextInt(backoff);
                sleep(sleepTime);
                if (2 * backoff < MAX_BACKOFF_DELAY_BEFORE_RETRY) {
                    backoff *= 2;
                }
            }
        } while (tryAgain);
        if (multicastIds.isEmpty()) {
            // all JSON posts failed due to GCM unavailability
            throw new IOException("Could not post JSON requests to GCM after "
                    + attempt + " attempts");
        }
        // calculate summary
        int success = 0, failure = 0 , canonicalIds = 0;
        for (Result result : results.values()) {
            if (result.getMessageId() != null) {
                success++;
                if (result.getCanonicalRegistrationId() != null) {
                    canonicalIds++;
                }
            } else {
                failure++;
            }
        }
        // build a new object with the overall result
        long multicastId = multicastIds.remove(0);
        return MulticastResult.builder()
                .success(success)
                .failure(failure)
                .canonicalIds(canonicalIds)
                .multicastId(multicastId)
                .retryMulticastIds(multicastIds)
                // add results, in the same order as the input
                .results(regIds.stream().map(results::get).collect(Collectors.toList()))
                .build();
    }

    /**
     * Updates the status of the messages sent to devices and the list of devices
     * that should be retried.
     *
     * @param unsentRegIds list of devices that are still pending an update.
     * @param allResults map of status that will be updated.
     * @param multicastResult result of the last multicast sent.
     *
     * @return updated version of devices that should be retried.
     */
    private List<String> updateStatus(List<String> unsentRegIds,
                                      Map<String, Result> allResults, MulticastResult multicastResult) {
        List<Result> results = multicastResult.getResults();
        if (results.size() != unsentRegIds.size()) {
            // should never happen, unless there is a flaw in the algorithm
            throw new RuntimeException("Internal error: sizes do not match. " +
                    "currentResults: " + results + "; unsentRegIds: " + unsentRegIds);
        }
        List<String> newUnsentRegIds = new ArrayList<String>();
        for (int i = 0; i < unsentRegIds.size(); i++) {
            String regId = unsentRegIds.get(i);
            Result result = results.get(i);
            allResults.put(regId, result);
            String error = result.getErrorCode();
            if (error != null && (error.equals(Constants.ERROR_UNAVAILABLE)
                    || error.equals(Constants.ERROR_INTERNAL_SERVER_ERROR))) {
                newUnsentRegIds.add(regId);
            }
        }
        return newUnsentRegIds;
    }

    /**
     * Sends a message without retrying in case of service unavailability. See
     * {@link #send(Message, List, int)} for more info.
     *
     * @return multicast results if the message was sent successfully,
     *         {@literal null} if it failed but could be retried.
     *
     * @throws IllegalArgumentException if registrationIds is {@literal null} or
     *         empty.
     * @throws InvalidRequestException if GCM didn't returned a 200 status.
     * @throws IOException if there was a JSON parsing error
     */
    public MulticastResult sendNoRetry(Message message,
                                       List<String> registrationIds) throws IOException {
        if (nonNull(registrationIds).isEmpty()) {
            throw new IllegalArgumentException("registrationIds cannot be empty");
        }
        Map<Object, Object> jsonRequest = new HashMap<Object, Object>();
        messageToMap(message, jsonRequest);
        jsonRequest.put(JSON_REGISTRATION_IDS, registrationIds);
        String responseBody = makeGcmHttpRequest(jsonRequest);
        if (responseBody == null) {
            return null;
        }
        JsonParser parser = new JsonParser();
        JsonObject jsonResponse;
        try {
            jsonResponse = (JsonObject) parser.parse(responseBody);
            int success = getNumber(jsonResponse, JSON_SUCCESS).intValue();
            int failure = getNumber(jsonResponse, JSON_FAILURE).intValue();
            int canonicalIds = getNumber(jsonResponse, JSON_CANONICAL_IDS).intValue();
            long multicastId = getNumber(jsonResponse, JSON_MULTICAST_ID).longValue();
            MulticastResult.MulticastResultBuilder builder = MulticastResult.builder()
                    .success(success)
                    .failure(failure)
                    .canonicalIds(canonicalIds)
                    .multicastId(multicastId);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) jsonResponse.get(JSON_RESULTS);
            if (results != null) {
                List<Result> resultList = results.stream()
                        .map(e -> {
                            String messageId = (String) e.get(JSON_MESSAGE_ID);
                            String canonicalRegId = (String) e.get(TOKEN_CANONICAL_REG_ID);
                            String error = (String) e.get(JSON_ERROR);
                            return Result.builder()
                                    .messageId(messageId)
                                    .canonicalRegistrationId(canonicalRegId)
                                    .errorCode(error)
                                    .build();
                        })
                        .collect(Collectors.toList());
                builder.results(resultList);
            }
            return builder.build();
        } catch (CustomParserException e) {
            throw newIoException(responseBody, e);
        }
    }

    private String makeGcmHttpRequest(Map<Object, Object> jsonRequest) throws InvalidRequestException {
        // Configure GSON
        final GsonBuilder gsonBuilder = new GsonBuilder();
        final Gson gson = gsonBuilder.create();

        // Format to JSON
        String requestBody = gson.toJson(jsonRequest);
        log.trace("JSON request: " + requestBody);
        HttpURLConnection conn;
        int status;
        try {
            conn = post(GCM_SEND_ENDPOINT, "application/json", requestBody);
            status = conn.getResponseCode();
        } catch (IOException e) {
            log.trace("IOException posting to GCM", e);
            return null;
        }
        String responseBody;
        if (status != 200) {
            try {
                responseBody = getAndClose(conn.getErrorStream());
                log.trace("JSON error response: " + responseBody);
            } catch (IOException e) {
                // ignore the exception since it will thrown an InvalidRequestException
                // anyways
                responseBody = "N/A";
                log.trace("Exception reading response: ", e);
            }
            throw new InvalidRequestException(status, responseBody);
        }
        try {
            responseBody = getAndClose(conn.getInputStream());
        } catch(IOException e) {
            log.warn("IOException reading response", e);
            return null;
        }
        log.trace("JSON response: " + responseBody);
        return responseBody;
    }

    /**
     * Populate Map with message.
     *
     * @param message Message used to populate Map.
     * @param mapRequest Map populated by Message.
     */
    private void messageToMap(Message message, Map<Object, Object> mapRequest) {
        if (message == null || mapRequest == null) {
            return;
        }
        setJsonField(mapRequest, PARAM_PRIORITY, message.getPriority());
        setJsonField(mapRequest, PARAM_CONTENT_AVAILABLE, message.getContentAvailable());
        setJsonField(mapRequest, PARAM_TIME_TO_LIVE, message.getTimeToLive());
        setJsonField(mapRequest, PARAM_COLLAPSE_KEY, message.getCollapseKey());
        setJsonField(mapRequest, PARAM_RESTRICTED_PACKAGE_NAME, message.getRestrictedPackageName());
        setJsonField(mapRequest, PARAM_DELAY_WHILE_IDLE, message.getDelayWhileIdle());
        setJsonField(mapRequest, PARAM_DRY_RUN, message.getDryRun());
        Map<String, String> payload = message.getData();
        if (!payload.isEmpty()) {
            mapRequest.put(JSON_PAYLOAD, payload);
        }
        if (message.getNotification() != null) {
            Notification notification = message.getNotification();
            Map<Object, Object> nMap = new HashMap<Object, Object>();
            if (notification.getBadge() != null) {
                setJsonField(nMap, JSON_NOTIFICATION_BADGE, notification.getBadge().toString());
            }
            setJsonField(nMap, JSON_NOTIFICATION_BODY, notification.getBody());
            setJsonField(nMap, JSON_NOTIFICATION_BODY_LOC_ARGS, notification.getBodyLocArgs());
            setJsonField(nMap, JSON_NOTIFICATION_BODY_LOC_KEY, notification.getBodyLocKey());
            setJsonField(nMap, JSON_NOTIFICATION_CLICK_ACTION, notification.getClickAction());
            setJsonField(nMap, JSON_NOTIFICATION_COLOR, notification.getColor());
            setJsonField(nMap, JSON_NOTIFICATION_ICON, notification.getIcon());
            setJsonField(nMap, JSON_NOTIFICATION_SOUND, notification.getSound());
            setJsonField(nMap, JSON_NOTIFICATION_TAG, notification.getTag());
            setJsonField(nMap, JSON_NOTIFICATION_TITLE, notification.getTitle());
            setJsonField(nMap, JSON_NOTIFICATION_TITLE_LOC_ARGS, notification.getTitleLocArgs());
            setJsonField(nMap, JSON_NOTIFICATION_TITLE_LOC_KEY, notification.getTitleLocKey());
            mapRequest.put(JSON_NOTIFICATION, nMap);
        }
    }

    private IOException newIoException(String responseBody, Exception e) {
        // log exception, as IOException constructor that takes a message and cause
        // is only available on Java 6
        String msg = "Error parsing JSON response (" + responseBody + ")";
        log.warn(msg, e);
        return new IOException(msg + ":" + e);
    }

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // ignore error
                log.trace("IOException closing stream", e);
            }
        }
    }

    /**
     * Sets a JSON field, but only if the value is not {@literal null}.
     */
    private void setJsonField(Map<Object, Object> json, String field,
                              Object value) {
        if (value != null) {
            json.put(field, value);
        }
    }

    private Number getNumber(JsonObject json, String field) {
        Object value = json.get(field);
        if (value == null) {
            throw new CustomParserException("Missing field: " + field);
        }
        if (!(value instanceof Number)) {
            throw new CustomParserException("Field " + field +
                    " does not contain a number: " + value);
        }
        return (Number) value;
    }

    class CustomParserException extends RuntimeException {
        CustomParserException(String message) {
            super(message);
        }
    }

    /**
     * Make an HTTP post to a given URL.
     *
     * @return HTTP response.
     */
    protected HttpURLConnection post(String url, String body)
            throws IOException {
        return post(url, "application/x-www-form-urlencoded;charset=UTF-8", body);
    }

    /**
     * Makes an HTTP POST request to a given endpoint.
     *
     * <p>
     * <strong>Note: </strong> the returned connected should not be disconnected,
     * otherwise it would kill persistent connections made using Keep-Alive.
     *
     * @param url endpoint to post the request.
     * @param contentType type of request.
     * @param body body of the request.
     *
     * @return the underlying connection.
     *
     * @throws IOException propagated from underlying methods.
     */
    protected HttpURLConnection post(String url, String contentType, String body)
            throws IOException {
        if (url == null || contentType == null || body == null) {
            throw new IllegalArgumentException("arguments cannot be null");
        }
        if (!url.startsWith("https://")) {
            log.warn("URL does not use https: " + url);
        }
        log.trace("Sending POST to " + url);
        log.trace("POST body: " + body);
        byte[] bytes = body.getBytes(UTF8);
        HttpURLConnection conn = getConnection(url);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Authorization", "key=" + key);
        OutputStream out = conn.getOutputStream();
        try {
            out.write(bytes);
        } finally {
            close(out);
        }
        return conn;
    }

    /**
     * Creates a map with just one key-value pair.
     */
    static final Map<String, String> newKeyValues(String key,
                                                            String value) {
        Map<String, String> keyValues = new HashMap<String, String>(1);
        keyValues.put(nonNull(key), nonNull(value));
        return keyValues;
    }

    /**
     * Creates a {@link StringBuilder} to be used as the body of an HTTP POST.
     *
     * @param name initial parameter for the POST.
     * @param value initial value for that parameter.
     * @return StringBuilder to be used an HTTP POST body.
     */
    static StringBuilder newBody(String name, String value) {
        return new StringBuilder(nonNull(name)).append('=').append(nonNull(value));
    }

    /**
     * Adds a new parameter to the HTTP POST body.
     *
     * @param body HTTP POST body.
     * @param name parameter's name.
     * @param value parameter's value.
     */
    static void addParameter(StringBuilder body, String name,
                                       String value) {
        nonNull(body).append('&')
                .append(nonNull(name)).append('=').append(nonNull(value));
    }

    /**
     * Gets an {@link HttpURLConnection} given an URL.
     */
    protected HttpURLConnection getConnection(String url) throws IOException {
        return (HttpURLConnection) new URL(url).openConnection();
    }

    /**
     * Convenience method to convert an InputStream to a String.
     * <p>
     * If the stream ends in a newline character, it will be stripped.
     * <p>
     * If the stream is {@literal null}, returns an empty string.
     */
    protected static String getString(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        StringBuilder content = new StringBuilder();
        String newLine;
        do {
            newLine = reader.readLine();
            if (newLine != null) {
                content.append(newLine).append('\n');
            }
        } while (newLine != null);
        if (content.length() > 0) {
            // strip last newline
            content.setLength(content.length() - 1);
        }
        return content.toString();
    }

    private static String getAndClose(InputStream stream) throws IOException {
        try {
            return getString(stream);
        } finally {
            if (stream != null) {
                close(stream);
            }
        }
    }

    static <T> T nonNull(T argument) {
        if (argument == null) {
            throw new IllegalArgumentException("argument cannot be null");
        }
        return argument;
    }

    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}