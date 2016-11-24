package com.google.fcm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.*;

import static com.google.fcm.SenderImpl.BACKOFF_INITIAL_DELAY_BEFORE_RETRY;
import static com.google.fcm.SenderImpl.MAX_BACKOFF_DELAY_BEFORE_RETRY;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author André Alexandre
 * @since 1.0.0
 */
@RunWith(MockitoJUnitRunner.class)
public class SenderTestOld {

    private final String regId = "15;16";
    private final String topic = "/topics/group";
    private final String collapseKey = "collapseKey";
    private final boolean delayWhileIdle = true;
    private final boolean dryRun = true;
    private final String restrictedPackageName = "package.name";
    private final int retries = 42;
    private final int ttl = 108;
    private final String authKey = "4815162342";
    private final JsonParser jsonParser = new JsonParser();

    private final Map<String, String> data = new LinkedHashMap<>();
    private final Message message =
            Message.builder()
                    .collapseKey(collapseKey)
                    .delayWhileIdle(delayWhileIdle)
                    .dryRun(dryRun)
                    .restrictedPackageName(restrictedPackageName)
                    .timeToLive(ttl)
                    .data(data)
                    .build();

    private final InputStream exceptionalStream = new InputStream() {

        @Override
        public int read() throws IOException {
            throw new IOException();
        }};

    // creates a Mockito Spy so we can stub internal methods
    @Spy
    private SenderImpl sender = new SenderImpl(authKey);

    @Mock
    private HttpURLConnection mockedConn;
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private Result result;

    @Before
    public void before() {
        result = Result.builder().build();
        data.put("k0", null);
        data.put(null, "v0");
        data.put("k1", "v1");
        data.put("k2", "v2");
        data.put("k3", "v3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_null() {
        new SenderImpl(null);
    }

    @Test
    public void testSend_noRetryOk() throws Exception {
        doNotSleep();
        doReturn(result).when(sender).sendNoRetry(message, regId);
        sender.send(message, regId, 0);
    }

    @Test(expected = IOException.class)
    public void testSend_noRetryFail() throws Exception {
        doNotSleep();
        doReturn(null).when(sender).sendNoRetry(message, regId);
        sender.send(message, regId, 0);
    }

    @Test(expected = IOException.class)
    public void testSend_noRetryException() throws Exception {
        doThrow(new IOException()).when(sender).sendNoRetry(message, regId);
        sender.send(message, regId, 0);
    }

    @Test
    public void testSend_retryOk() throws Exception {
        doNothing().when(sender).sleep(anyInt());
        doReturn(null) // fails 1st time
                .doReturn(null) // fails 2nd time
                .doReturn(result) // succeeds 3rd time
                .when(sender).sendNoRetry(message, regId);
        sender.send(message, regId, 2);
        verify(sender, times(3)).sendNoRetry(message, regId);
    }

    @Test(expected = IOException.class)
    public void testSend_retryFails() throws Exception {
        doNothing().when(sender).sleep(anyInt());
        doReturn(null) // fails 1st time
                .doReturn(null) // fails 2nd time
                .doReturn(null) // fails 3rd time
                .when(sender).sendNoRetry(message, regId);
        sender.send(message, regId, 2);
        verify(sender, times(3)).sendNoRetry(message, regId);
    }

    @Test
    public void testSend_retryExponentialBackoff() throws Exception {
        ArgumentCaptor<Long> capturedSleep = ArgumentCaptor.forClass(Long.class);
        int total = retries + 1; // fist attempt + retries
        doNothing().when(sender).sleep(anyInt());
        doReturn(null).when(sender).sendNoRetry(message, regId);
        try {
            sender.send(message, regId, retries);
            fail("Should have thrown IOEXception");
        } catch (IOException e) {
            String message = e.getMessage();
            assertTrue("invalid message:" + message, message.contains("" + total));
        }
        verify(sender, times(total)).sendNoRetry(message, regId);
        verify(sender, times(retries)).sleep(capturedSleep.capture());
        long backoffRange = BACKOFF_INITIAL_DELAY_BEFORE_RETRY;
        for (long value : capturedSleep.getAllValues()) {
            assertTrue(value >= backoffRange / 2);
            assertTrue(value <= backoffRange * 3 / 2);
            if (2 * backoffRange < MAX_BACKOFF_DELAY_BEFORE_RETRY) {
                backoffRange *= 2;
            }
        }
    }

    @Test
    public void testSendNoRetry_ok() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'multicast_id': 108,"
                + "  'success': 1,"
                + "  'failure': 0,"
                + "  'canonical_ids': 0,"
                + "  'results': ["
                + "    {'message_id': '4815162342'}, "
                + "  ]"
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, regId);
        assertNotNull(result);
        assertEquals("4815162342", result.getMessageId());
        assertNull(result.getCanonicalRegistrationId());
        assertNull(result.getErrorCode());
    }

    @Test
    public void testSendNoRetry_topic_ok() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'message_id': 4815162342 "
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, topic);
        assertNotNull(result);
        assertEquals("4815162342", result.getMessageId());
        assertNull(result.getCanonicalRegistrationId());
        assertNull(result.getErrorCode());
    }

    @Test
    public void testSendNoRetry_group_ok() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "'success': 3,"
                + "'failure': 0"
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, regId);
        assertNotNull(result);
        assertEquals(3, result.getSuccess().intValue());
        assertEquals(0, result.getFailure().intValue());
        assertNull(result.getFailedRegistrationIds());
    }

    @Test
    public void testSendNoRetry_ok_canonical() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'multicast_id': 108,"
                + "  'success': 1,"
                + "  'failure': 0,"
                + "  'canonical_ids': 1,"
                + "  'results': ["
                + "    {'message_id': '23', 'registration_id': '42'}"
                + "  ]"
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, regId);
        assertNotNull(result);
        assertEquals("23", result.getMessageId());
        assertEquals("42", result.getCanonicalRegistrationId());
        assertNull(result.getErrorCode());
    }

    @Test
    public void testSendNoRetry_unauthorized() throws Exception {
        setResponseExpectations(401, "");
        try {
            sender.sendNoRetry(message, regId);
            fail("Should have thrown InvalidRequestException");
        } catch (InvalidRequestException e) {
            assertEquals(401, e.getHttpStatusCode());
        }
    }

    @Test
    public void testSendNoRetry_unauthorized_nullStream() throws Exception {
        setResponseExpectations(401, null);
        try {
            sender.sendNoRetry(message, regId);
            fail("Should have thrown InvalidRequestException");
        } catch (InvalidRequestException e) {
            assertEquals(401, e.getHttpStatusCode());
            assertEquals("", e.getDescription());
        }
    }

    @Test
    public void testSendNoRetry_error() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'multicast_id': 108,"
                + "  'success': 0,"
                + "  'failure': 1,"
                + "  'canonical_ids': 0,"
                + "  'results': ["
                + "    {'error': 'DOH!'} "
                + "  ]"
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, regId);
        assertNull(result.getMessageId());
        assertNull(result.getCanonicalRegistrationId());
        assertEquals("DOH!", result.getErrorCode());
    }

    @Test
    public void testSendNoRetry_topic_error() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + " 'error': 'MissingRegistration' "
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, topic);
        assertNull(result.getMessageId());
        assertEquals("MissingRegistration", result.getErrorCode());
    }

    @Test
    public void testSendNoRetry_group_error() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "'success': 3,"
                + "'failure': 2,"
                + "'failed_registration_ids': ["
                + " 'reg_id1', 'reg_id2'"
                + " ]"
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, regId);
        assertNotNull(result);
        assertEquals(3, result.getSuccess().intValue());
        assertEquals(2, result.getFailure().intValue());
        assertEquals(2, result.getFailedRegistrationIds().size());
    }

    @Test
    public void testSendNoRetry_resultsCount() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'multicast_id': 108,"
                + "  'success': 2,"
                + "  'failure': 0,"
                + "  'canonical_ids': 0,"
                + "  'results': ["
                + "    {'message_id': '4815162342'}, "
                + "    {'message_id': '4815162343'}, "
                + "  ]"
                + "}");
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, regId);
        assertNull(result);
    }

    @Test
    public void testSendNoRetry_emptyResult() throws Exception {
        String json = "{}";
        setResponseExpectations(200, json);
        Result result = sender.sendNoRetry(message, topic);
        assertNull(result);
    }

    @Test
    public void testSendNoRetry_serviceUnavailable() throws Exception {
        setResponseExpectations(503, "");
        try {
            sender.sendNoRetry(message, regId);
            fail("Should have thrown InvalidRequestException");
        } catch (InvalidRequestException e) {
            assertEquals(503, e.getHttpStatusCode());
            assertEquals("", e.getDescription());
        }
    }

    @Test
    public void testSendNoRetry_internalServerError() throws Exception {
        setResponseExpectations(500, "");
        try {
            sender.sendNoRetry(message, regId);
            fail("Should have thrown InvalidRequestException");
        } catch (InvalidRequestException e) {
            assertEquals(500, e.getHttpStatusCode());
            assertEquals("", e.getDescription());
        }
    }

    @Test
    public void testSendNoRetry_ioException_post() throws Exception {
        when(mockedConn.getOutputStream()).thenThrow(new IOException());
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
        Result result = sender.sendNoRetry(message, regId);
        assertNull(result);
    }

    @Test
    public void testSendNoRetry_ioException_errorStream() throws Exception {
        when(mockedConn.getResponseCode()).thenReturn(42);
        when(mockedConn.getOutputStream()).thenReturn(outputStream);
        when(mockedConn.getErrorStream()).thenReturn(exceptionalStream);
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
        try {
            sender.sendNoRetry(message, regId);
        } catch (InvalidRequestException e) {
            assertEquals(42, e.getHttpStatusCode());
        }
    }

    @Test
    public void testSendNoRetry_ioException_inputStream() throws Exception {
        when(mockedConn.getResponseCode()).thenReturn(200);
        when(mockedConn.getOutputStream()).thenReturn(outputStream);
        when(mockedConn.getInputStream()).thenReturn(exceptionalStream);
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
        Result result = sender.sendNoRetry(message, regId);
        assertNull(result);
    }

    @Test(expected = IOException.class)
    public void testSendNoRetry_emptyBody() throws Exception {
        setResponseExpectations(200, "");
        sender.sendNoRetry(message, regId);
    }

    @Test
    public void testSendNoRetry_invalidHttpStatusCode() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'multicast_id': 108,"
                + "  'success': 1,"
                + "  'failure': 0,"
                + "  'canonical_ids': 0,"
                + "  'results': ["
                + "    {'message_id': '16'}, "
                + "  ]"
                + "}");
        setResponseExpectations(108, json);
        try {
            sender.sendNoRetry(message, regId);
        } catch (InvalidRequestException e) {
            assertEquals(108, e.getHttpStatusCode());
            assertEquals(json, e.getDescription());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSendNoRetry_noRegistrationId() throws Exception {
        sender.sendNoRetry(Message.builder().build(), (String) null);
    }

    @Test()
    public void testSend_json_failsPostingJSON_null() throws Exception {
        List<String> regIds = Arrays.asList("108");
        doReturn(null).when(sender).sendNoRetry(message, regIds);
        try {
            sender.send(message, regIds, 0);
        } catch(IOException e) {
            assertNotNull(e.getMessage());
        }
        verify(sender, times(1)).sendNoRetry(message, regIds);
    }

    @Test()
    public void testSend_json_failsPostingJSON_IOException() throws Exception {
        List<String> regIds = Arrays.asList("108");
        IOException gcmException = new IOException();
        doThrow(gcmException).when(sender).sendNoRetry(message, regIds);
        try {
            sender.send(message, regIds, 0);
        } catch(IOException e) {
            assertNotNull(e.getMessage());
            assertNotSame(gcmException, e);
        }
        verify(sender, times(1)).sendNoRetry(message, regIds);
    }

    @Test()
    public void testSend_json_allAttemptsFail() throws Exception {
        doNothing().when(sender).sleep(anyInt());
        // mock sendNoRetry
        Result unaivalableResult = Result.builder().errorCode("Unavailable").build();
        // for the intermediate request, only the multicast id matters
        MulticastResult mockedResult = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(42)
                .results(Collections.singletonList(unaivalableResult))
                .build();
        List<String> regIds = Arrays.asList("108");
        doReturn(mockedResult).when(sender).sendNoRetry(message, regIds);
        MulticastResult actualResult = sender.send(message, regIds, 2);
        assertNotNull(actualResult);
        assertEquals(1, actualResult.getTotal());
        assertEquals(0, actualResult.getSuccess());
        assertEquals(1, actualResult.getFailure());
        assertEquals(0, actualResult.getCanonicalIds());
        assertEquals(42, actualResult.getMulticastId());
        assertEquals(1, actualResult.getResults().size());
        assertResult(actualResult.getResults().get(0), null, "Unavailable", null);
        verify(sender, times(3)).sendNoRetry(message, regIds);
    }

    @Test()
    public void testSend_json_secondAttemptOk() throws Exception {
        doNothing().when(sender).sleep(anyInt());
        // mock sendNoRetry
        Result unaivalableResult = Result.builder().errorCode("Unavailable").build();
        Result okResult = Result.builder().messageId("42").build();
        // for the intermediate request, only the multicast id matters
        MulticastResult mockedResult1 = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(100)
                .results(Collections.singletonList(unaivalableResult)).build();
        MulticastResult mockedResult2 = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(200)
                .results(Collections.singletonList(okResult)).build();
        List<String> regIds = Arrays.asList("108");
        doReturn(mockedResult1) // fist time it fails
                .doReturn(mockedResult2) // second time it succeeds
                .when(sender).sendNoRetry(message, regIds);
        MulticastResult actualResult = sender.send(message, regIds, 10);
        assertNotNull(actualResult);
        assertEquals(1, actualResult.getTotal());
        assertEquals(1, actualResult.getSuccess());
        assertEquals(0, actualResult.getFailure());
        assertEquals(0, actualResult.getCanonicalIds());
        assertEquals(100, actualResult.getMulticastId());
        assertEquals(1, actualResult.getResults().size());
        assertResult(actualResult.getResults().get(0), "42", null, null);
        List<Long> retryMulticastIds = actualResult.getRetryMulticastIds();
        assertEquals(1, retryMulticastIds.size());
        assertEquals(200, retryMulticastIds.get(0).longValue());
        verify(sender, times(2)).sendNoRetry(message, regIds);
    }

    @Test()
    public void testSend_json_ok() throws Exception {
        doNothing().when(sender).sleep(anyInt());
    /*
     * The following scenario is mocked below:
     *
     * input: 4, 8, 15, 16, 23, 42
     *
     * 1st call (multicast_id:100): 4,16:ok 8,15: unavailable
     *                              23:internalServerError, 42:error,
     * 2nd call: whole post failed
     * 3rd call (multicast_id:200): 8,15: unavailable, 23:ok
     * 4th call (multicast_id:300): 8:error, 15:unavailable
     * 5th call (multicast_id:400): 15:unavailable
     *
     * output: total:6, success:3, error: 3, canonicals: 0, multicast_id: 100
     *         results: ok, error, unavailable, ok, ok, error
     */
        Result unaivalableResult = Result.builder().errorCode("Unavailable").build();
        Result internalServerErrorResult = Result.builder().errorCode("InternalServerError").build();
        Result errorResult = Result.builder().errorCode("D'OH!").build();
        Result okResultMsg4 = Result.builder().messageId("msg4").build();
        Result okResultMsg16 = Result.builder().messageId("msg16").build();
        Result okResultMsg23 = Result.builder().messageId("msg23").build();
        MulticastResult result1stCall = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(100)
                .results(
                    Arrays.asList(
                        okResultMsg4,
                        unaivalableResult,
                        unaivalableResult,
                        okResultMsg16,
                        internalServerErrorResult,
                        errorResult
                    )
                )
                .build();
        doReturn(result1stCall).when(sender).sendNoRetry(message,
                Arrays.asList("4", "8", "15", "16", "23", "42"));
        MulticastResult result2ndCall = null;
        MulticastResult result3rdCall = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(200)
                .results(
                    Arrays.asList(
                        unaivalableResult,
                        unaivalableResult,
                        okResultMsg23
                    )
                )
                .build();
        // must next 2nd and 3rd calls on same mock setup since input is the same
        doReturn(result2ndCall).doReturn(result3rdCall).when(sender)
                .sendNoRetry(message, Arrays.asList("8", "15", "23"));
        MulticastResult result4thCall = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(300)
                .results(
                    Arrays.asList(
                        errorResult,
                        unaivalableResult
                    )
                )
                .build();
        doReturn(result4thCall).when(sender).sendNoRetry(message,
                Arrays.asList("8", "15"));
        MulticastResult result5thCall = MulticastResult.builder()
                .success(0).failure(0).canonicalIds(0).multicastId(400)
                .results(Collections.singletonList(unaivalableResult))
                .build();
        doReturn(result5thCall).when(sender).sendNoRetry(message,
                Arrays.asList("15"));

        // call it
        MulticastResult actualResult = sender.send(message,
                Arrays.asList("4", "8", "15", "16", "23", "42"), 4);

        // assert results
        assertNotNull(actualResult);
        assertEquals(6, actualResult.getTotal());
        assertEquals(3, actualResult.getSuccess());
        assertEquals(3, actualResult.getFailure());
        assertEquals(0, actualResult.getCanonicalIds());
        assertEquals(100, actualResult.getMulticastId());
        List<Result> actualResults = actualResult.getResults();
        assertEquals(6, actualResults.size());
        assertResult(actualResults.get(0), "msg4", null, null); // 4
        assertResult(actualResults.get(1), null, "D'OH!", null); // 8
        assertResult(actualResults.get(2), null, "Unavailable", null); // 15
        assertResult(actualResults.get(3), "msg16", null, null); // 16
        assertResult(actualResults.get(4), "msg23", null, null); // 23
        assertResult(actualResults.get(5), null, "D'OH!", null); // 42
        List<Long> retryMulticastIds = actualResult.getRetryMulticastIds();
        assertEquals(3, retryMulticastIds.size());
        assertEquals(200, retryMulticastIds.get(0).longValue());
        assertEquals(300, retryMulticastIds.get(1).longValue());
        assertEquals(400, retryMulticastIds.get(2).longValue());
        verify(sender, times(5)).sendNoRetry(eq(message), anyListOf(String.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSendNoRetry_json_nullRegIds() throws Exception {
        sender.sendNoRetry(message, (List<String>) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSendNoRetry_json_emptyRegIds() throws Exception {
        sender.sendNoRetry(message, Collections.<String>emptyList());
    }

    @Test
    public void testSendNoRetry_json_badRequest() throws Exception {
        setResponseExpectations(42, "bad json");
        try {
            sender.sendNoRetry(message, Arrays.asList("108"));
        } catch (InvalidRequestException e) {
            assertEquals(42, e.getHttpStatusCode());
            assertEquals("bad json", e.getDescription());
            assertRequestJsonBody("108");
        }
    }

    @Test
    public void testSendNoRetry_json_badRequest_nullError() throws Exception {
        setResponseExpectations(42, null);
        try {
            sender.sendNoRetry(message, Arrays.asList("108"));
        } catch (InvalidRequestException e) {
            assertEquals(42, e.getHttpStatusCode());
            assertEquals("", e.getDescription());
            assertRequestJsonBody("108");
        }
    }

    @Test
    public void testSendNoRetry_json_ioException_post() throws Exception {
        when(mockedConn.getOutputStream()).thenThrow(new IOException());
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
        MulticastResult multicastResult = sender.sendNoRetry(message,
                Arrays.asList("4", "8", "15"));
        assertNull(multicastResult);
    }

    @Test
    public void testSendNoRetry_json_ioException_errorStream() throws Exception {
        when(mockedConn.getResponseCode()).thenReturn(42);
        when(mockedConn.getOutputStream()).thenReturn(outputStream);
        when(mockedConn.getErrorStream()).thenReturn(exceptionalStream);
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
        try {
            sender.sendNoRetry(message, Arrays.asList("4", "8", "15"));
        } catch (InvalidRequestException e) {
            assertEquals(42, e.getHttpStatusCode());
        }
    }

    @Test
    public void testSendNoRetry_json_ioException_inputStream() throws Exception {
        when(mockedConn.getResponseCode()).thenReturn(200);
        when(mockedConn.getOutputStream()).thenReturn(outputStream);
        when(mockedConn.getInputStream()).thenReturn(exceptionalStream);
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
        MulticastResult multicastResult = sender.sendNoRetry(message,
                Arrays.asList("4", "8", "15"));
        assertNull(multicastResult);
    }

    @Test()
    public void testSendNoRetry_json_ok() throws Exception {
        String json = replaceQuotes("\n"
                + "{"
                + "  'multicast_id': 108,"
                + "  'success': 2,"
                + "  'failure': 1,"
                + "  'canonical_ids': 1,"
                + "  'results': ["
                + "    {'message_id': '16'}, "
                + "    {'error': 'DOH!'}, "
                + "    {'message_id': '23', 'registration_id': '42'}"
                + "  ]"
                + "}");
        setResponseExpectations(200, json);
        MulticastResult multicastResult = sender.sendNoRetry(message,
                Arrays.asList("4", "8", "15"));
        assertNotNull(multicastResult);
        assertEquals(3, multicastResult.getTotal());
        assertEquals(2, multicastResult.getSuccess());
        assertEquals(1, multicastResult.getFailure());
        assertEquals(1, multicastResult.getCanonicalIds());
        assertEquals(108, multicastResult.getMulticastId());
        List<Result> results = multicastResult.getResults();
        assertNotNull(results);
        assertEquals(3, results.size());
        assertResult(results.get(0), "16", null, null);
        assertResult(results.get(1), null, "DOH!", null);
        assertResult(results.get(2), "23", null, "42");
        assertRequestJsonBody("4", "8", "15");
    }

    // replace ' by ", otherwise JSON strins would need to escape double-quotes
    private String replaceQuotes(String json) {
        return json.replaceAll("'", "\"");
    }

    private void assertResult(Result result, String messageId, String error,
                              String canonicalRegistrationId) {
        assertEquals(messageId, result.getMessageId());
        assertEquals(error, result.getErrorCode());
        assertEquals(canonicalRegistrationId, result.getCanonicalRegistrationId());
    }

    private void assertRequestJsonBody(String...expectedRegIds) throws Exception {
        ArgumentCaptor<String> capturedBody = ArgumentCaptor.forClass(String.class);
        verify(sender).post(eq(Constants.GCM_SEND_ENDPOINT), eq("application/json"),
                capturedBody.capture());
        // parse body
        String body = capturedBody.getValue();
        JsonObject json = (JsonObject) jsonParser.parse(body);
        assertEquals(ttl, (long) json.get("time_to_live").getAsInt());
        assertEquals(collapseKey, json.get("collapse_key"));
        assertEquals(delayWhileIdle, json.get("delay_while_idle"));
        assertEquals(dryRun, json.get("dry_run"));
        assertEquals(restrictedPackageName, json.get("restricted_package_name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) json.get("data");
        assertNotNull("no payload", payload);
        assertEquals("wrong payload size", 5, payload.size());
        assertEquals("v0", payload.get("null"));
        assertNull(payload.get("v0"));
        assertEquals("v1", payload.get("k1"));
        assertEquals("v2", payload.get("k2"));
        assertEquals("v3", payload.get("k3"));
        JsonArray actualRegIds = (JsonArray) json.get("registration_ids");
        assertEquals("Wrong number of regIds",
                expectedRegIds.length, actualRegIds.size());
        for (int i = 0; i < expectedRegIds.length; i++) {
            String expectedRegId = expectedRegIds[i];
            String actualRegId = actualRegIds.get(i).getAsString();
            assertEquals("invalid regId at index " + i, expectedRegId, actualRegId);
        }
    }

    @Test
    public void testNewKeyValues() {
        Map<String, String> x = SenderImpl.newKeyValues("key", "value");
        assertEquals(1, x.size());
        assertEquals("value", x.get("key"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNewKeyValues_nullKey() {
        SenderImpl.newKeyValues(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNewKeyValues_nullValue() {
        SenderImpl.newKeyValues("key", null);
    }

    @Test
    public void testNewBody() {
        StringBuilder body = SenderImpl.newBody("name", "value");
        assertEquals("name=value", body.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNewBody_nullKey() {
        SenderImpl.newBody(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNewBody_nullValue() {
        SenderImpl.newBody("key", null);
    }

    @Test
    public void testAddParameter() {
        StringBuilder body = new StringBuilder("P=NP");
        SenderImpl.addParameter(body, "name", "value");
        assertEquals("P=NP&name=value", body.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddParameter_nullBody() {
        SenderImpl.addParameter(null, "key", "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddParameter_nullKey() {
        StringBuilder body = new StringBuilder();
        SenderImpl.addParameter(body, null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddParameter_nullValue() {
        StringBuilder body = new StringBuilder();
        SenderImpl.addParameter(body, "key", null);
    }

    @Test
    public void testGetString_oneLine() throws Exception {
        String expected = "108";
        InputStream stream = new ByteArrayInputStream(expected.getBytes());
        String actual = SenderImpl.getString(stream);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetString_stripsLastLine() throws Exception {
        InputStream stream = new ByteArrayInputStream("108\n".getBytes());
        String stripped = SenderImpl.getString(stream);
        assertEquals("108", stripped);
    }

    @Test
    public void testGetString_multipleLines() throws Exception {
        String expected = "4\n8\n15\n\n16\n23\n42";
        InputStream stream = new ByteArrayInputStream(expected.getBytes());
        String actual = SenderImpl.getString(stream);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetString_nullValue() throws Exception {
        assertEquals("", SenderImpl.getString(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPost_noUrl() throws Exception {
        sender.post(null, "whatever", "whatever");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPost_noBody() throws Exception {
        sender.post(Constants.GCM_SEND_ENDPOINT, "whatever", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPost_noType() throws Exception {
        sender.post(Constants.GCM_SEND_ENDPOINT, null, "whatever");
    }

    @Test
    public void testPost() throws Exception {
        String requestBody = "マルチバイト文字";
        String responseBody = "resp";
        setResponseExpectations(200, responseBody);
        HttpURLConnection response =
                sender.post(Constants.GCM_SEND_ENDPOINT, requestBody);
        assertEquals(requestBody, new String(outputStream.toByteArray()));
        verify(mockedConn).setRequestMethod("POST");
        verify(mockedConn).setFixedLengthStreamingMode(requestBody.getBytes("UTF-8").length);
        verify(mockedConn).setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded;charset=UTF-8");
        verify(mockedConn).setRequestProperty("Authorization", "key=" + authKey);
        assertEquals(200, response.getResponseCode());
    }

    @Test
    public void testPost_customType() throws Exception {
        String requestBody = "マルチバイト文字";
        String responseBody = "resp";
        setResponseExpectations(200, responseBody);
        HttpURLConnection response =
                sender.post(Constants.GCM_SEND_ENDPOINT, "stuff", requestBody);
        assertEquals(requestBody, new String(outputStream.toByteArray()));
        verify(mockedConn).setRequestMethod("POST");
        verify(mockedConn).setFixedLengthStreamingMode(requestBody.getBytes("UTF-8").length);
        verify(mockedConn).setRequestProperty("Content-Type", "stuff");
        verify(mockedConn).setRequestProperty("Authorization", "key=" + authKey);
        assertEquals(200, response.getResponseCode());
    }

    /**
     * Sets the expectations of the HTTP connection.
     */
    private void setResponseExpectations(int statusCode, String response)
            throws IOException {
        when(mockedConn.getResponseCode()).thenReturn(statusCode);
        InputStream inputStream = (response == null) ?
                null : new ByteArrayInputStream(response.getBytes());
        if (statusCode == 200) {
            when(mockedConn.getInputStream()).thenReturn(inputStream);
        } else {
            when(mockedConn.getErrorStream()).thenReturn(inputStream);
        }
        when(mockedConn.getOutputStream()).thenReturn(outputStream);
        doReturn(mockedConn).when(sender)
                .getConnection(Constants.GCM_SEND_ENDPOINT);
    }

    private void doNotSleep() {
        doThrow(new AssertionError("Thou should not sleep!")).when(sender)
                .sleep(anyInt());
    }

}